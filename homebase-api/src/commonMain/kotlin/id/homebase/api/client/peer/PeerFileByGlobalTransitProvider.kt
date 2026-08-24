package id.homebase.api.client.peer

import co.touchlab.kermit.Logger
import id.homebase.api.client.ByteApiResponse
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.PayloadSizePolicy
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.BytesResponse
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.common.OdinId
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.Headers
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

// Feed posts from people you follow land on the local feed drive as header-only references — the media bytes
// stay on the author's drive — so displaying their images needs a remote read.
//
// Preferred route is the CDN: the edge fetches the author's host directly, so the user's own identity server
// stops brokering every follower's view. Falls back to the peer route, which is the only option for drives
// that aren't CDN-enabled and for hosts that reject the worker's CDN token.
//
// Decryption uses the [keyHeader] the caller already holds from its feed-drive row, not the
// `sharedsecretencryptedheader64` the broker re-encrypts per request. Feed distribution preserves the
// author's file AES key end to end (one key per file, one iv per payload), so the two carry the same key —
// and only the caller-supplied one survives a disk-cache hit or a CDN response, neither of which has that
// header.
//
// Full payload reads are size-guarded at PayloadSizePolicy.RENDER_LIMIT_BYTES so a followed identity's
// oversized photo can't be buffered into RAM; thumbnails stay uncapped, matching the local read.
//
// The remote resolves the drive by ALIAS only (Type is ignored), so [driveId] is the channel drive's alias.
class PeerFileByGlobalTransitProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
    private val driveFileHttpProvider: DriveFileHttpProvider,
    private val driveCache: DriveFileProviderCached,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    // Every Odin host advertises its CDN base on every response, and one worker serves the fleet, so the
    // value learned from our own host also addresses the author's. ponytail: assumes a single fleet-wide
    // CDN — if identities ever point at different workers this must be read from the author's host instead.
    private var cdnBase: String? = null

    // Hosts whose CDN read failed. A host that doesn't share the worker's CDN token hard-401s with no
    // anonymous fallback, and a drive without AllowCdn 404s — both permanent, so retrying per image would
    // double every request. Session-scoped; a restart re-probes.
    private val peerOnlyHosts = mutableSetOf<String>()
    private val cdnStateLock = Mutex()

    /** Null on 404. */
    suspend fun getPayloadOverPeerByGlobalTransitId(
        peer: OdinId,
        driveId: Uuid,
        globalTransitId: Uuid,
        payloadKey: String,
        keyHeader: KeyHeader,
    ): BytesResponse? {
        require(payloadKey.isNotBlank()) { "payloadKey must be defined" }
        val creds = requireCreds()
        val remotePath = "/drives/$driveId/files/by-gtid/$globalTransitId/payload/$payloadKey"
        val peerUrl = apiUrl(creds.domain, "/peer/$peer$remotePath")
        val cacheKey = peerCacheKey("payload", peer, driveId, globalTransitId, payloadKey)
        val response = try {
            driveCache.readPayloadThrough(cacheKey) {
                fetchViaCdnOrPeer(
                    peer, remotePath, peerUrl, creds.accessToken,
                    PayloadSizePolicy.RENDER_LIMIT_BYTES,
                )
            }
        } catch (_: NotFoundException) {
            Logger.i(tag = TAG) { "404 (not shared / missing) url=$peerUrl" }
            return null
        }
        return decrypted(response, keyHeader)
    }

    /** Null on 404. */
    suspend fun getThumbOverPeerByGlobalTransitId(
        peer: OdinId,
        driveId: Uuid,
        globalTransitId: Uuid,
        payloadKey: String,
        width: Int,
        height: Int,
        keyHeader: KeyHeader,
    ): BytesResponse? {
        require(payloadKey.isNotBlank()) { "payloadKey must be defined" }
        require(width > 0 && height > 0) { "width/height must be positive" }
        val creds = requireCreds()
        val base = "/drives/$driveId/files/by-gtid/$globalTransitId/payload/$payloadKey"
        // The by-gtid route takes the size as query params; the peer broker takes it as path segments.
        val remotePath = "$base/thumb?width=$width&height=$height"
        val peerUrl = apiUrl(creds.domain, "/peer/$peer$base/thumb/$width/$height")
        val cacheKey =
            peerCacheKey("thumb", peer, driveId, globalTransitId, payloadKey, width, height)
        val response = try {
            driveCache.readThumbThrough(cacheKey) {
                fetchViaCdnOrPeer(peer, remotePath, peerUrl, creds.accessToken, maxBytes = null)
            }
        } catch (_: NotFoundException) {
            Logger.i(tag = TAG) { "404 (not shared / missing) url=$peerUrl" }
            return null
        }
        return decrypted(response, keyHeader)
    }

    // Runs inside the disk cache's fill lambda, so the CDN attempt and the peer fallback share one cache
    // entry and one 404 memo. Memoising a CDN-only 404 under that key would strand a file the peer route
    // can still serve.
    private suspend fun fetchViaCdnOrPeer(
        peer: OdinId,
        remotePath: String,
        peerUrl: String,
        token: String,
        maxBytes: Long?,
    ): ByteApiResponse {
        var cdnMissedButPeerMightNot = false
        cdnUrlFor(peer, remotePath)?.let { cdnUrl ->
            try {
                Logger.i(tag = TAG) { "CDN GET $cdnUrl" }
                val response = requestBytes(maxBytes) { httpClient.get(cdnUrl) }
                throwForFailure(response)
                return response
            } catch (e: CancellationException) {
                throw e
            } catch (_: NotFoundException) {
                // Could be a genuinely missing file (peer will 404 too) or a drive without AllowCdn
                // (peer serves it). Distinguished below, once we know what peer says.
                cdnMissedButPeerMightNot = true
            } catch (e: Exception) {
                markPeerOnly(peer)
                Logger.i(tag = TAG) {
                    "CDN read failed (${e::class.simpleName}) — peer-only for $peer"
                }
            }
        }

        val response = fetch(peerUrl, token, maxBytes)
        // Peer served what the CDN 404'd, so the drive isn't CDN-enabled. Stop paying the extra hop.
        // If peer had 404'd too, fetch() would have thrown and the cache memoises it instead.
        if (cdnMissedButPeerMightNot) markPeerOnly(peer)
        return response
    }

    private suspend fun fetch(url: String, token: String, maxBytes: Long?): ByteApiResponse {
        // Only reached on a cache miss, so one line here is exactly one network read.
        Logger.i(tag = TAG) { "GET $url" }
        val response = requestBytes(maxBytes) { httpClient.get(url) { bearerAuth(token) } }
        learnCdnBase(response.headers)
        // 404 becomes NotFoundException, which the cache memoises so a followed post with no
        // server-side thumbnail stops being re-requested on every scroll past it.
        throwForFailure(response)
        return response
    }

    private suspend fun cdnUrlFor(peer: OdinId, remotePath: String): String? =
        cdnStateLock.withLock {
            val base = cdnBase ?: return@withLock null
            if (peer.toString() in peerOnlyHosts) return@withLock null
            "$base/?forward=${"https://$peer/api/v2$remotePath".encodeURLParameter()}"
        }

    private suspend fun learnCdnBase(headers: Headers) {
        if (cdnBase != null) return
        val advertised = headers[CDN_BASE_HEADER]?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return
        cdnStateLock.withLock { if (cdnBase == null) cdnBase = advertised }
    }

    private suspend fun markPeerOnly(peer: OdinId) {
        cdnStateLock.withLock { peerOnlyHosts.add(peer.toString()) }
    }

    private suspend fun decrypted(response: ByteApiResponse, keyHeader: KeyHeader): BytesResponse? {
        if (response.status == 404) return null
        val bytes = driveFileHttpProvider.decryptBytes(keyHeader, response.headers, response.bytes)
        return BytesResponse(bytes = bytes, contentType = response.contentType)
    }

    // The peer segment keeps these off the own-drive keys: a followed post has no local fileId, and
    // two identities can publish the same gtid on same-aliased channel drives. The key is route-agnostic
    // so a CDN read and a peer read of the same bytes share one cache entry.
    private fun peerCacheKey(kind: String, vararg parts: Any): String =
        (listOf(kind, "peer") + parts.toList()).joinToString(":")

    companion object {
        private const val TAG = "PeerFileByGtid"
        private const val CDN_BASE_HEADER = "x-odin-cdn-payload"
    }
}
