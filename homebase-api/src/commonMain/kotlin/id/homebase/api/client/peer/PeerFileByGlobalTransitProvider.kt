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
import kotlin.uuid.Uuid

// Feed posts from people you follow land on the local feed drive as header-only references — the media bytes
// stay on the author's drive — so displaying their images needs this read over peer. The user's own host
// brokers the request.
//
// Decryption uses the [keyHeader] the caller already holds from its feed-drive row, not the
// `sharedsecretencryptedheader64` the broker re-encrypts per request. Feed distribution preserves the
// author's file AES key end to end (one key per file, one iv per payload), so the two carry the same key —
// and only the caller-supplied one survives a disk-cache hit, where no response headers exist.
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
        val url = apiUrl(
            creds.domain,
            "/peer/$peer/drives/$driveId/files/by-gtid/$globalTransitId/payload/$payloadKey",
        )
        val cacheKey = peerCacheKey("payload", peer, driveId, globalTransitId, payloadKey)
        Logger.i(tag = TAG) {
            "getPayload: GET peer=${peer.domainName} drive=$driveId gtid=$globalTransitId key=$payloadKey"
        }
        val response = try {
            driveCache.readPayloadThrough(cacheKey) {
                fetch(url, creds.accessToken, PayloadSizePolicy.RENDER_LIMIT_BYTES)
            }
        } catch (_: NotFoundException) {
            Logger.i(tag = TAG) { "404 (not shared / missing) url=$url" }
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
        val url = apiUrl(
            creds.domain,
            "/peer/$peer/drives/$driveId/files/by-gtid/$globalTransitId/payload/$payloadKey/thumb/$width/$height",
        )
        val cacheKey =
            peerCacheKey("thumb", peer, driveId, globalTransitId, payloadKey, width, height)
        Logger.i(tag = TAG) {
            "getThumb: GET peer=${peer.domainName} drive=$driveId gtid=$globalTransitId key=$payloadKey ${width}x$height"
        }
        val response = try {
            driveCache.readThumbThrough(cacheKey) { fetch(url, creds.accessToken, maxBytes = null) }
        } catch (_: NotFoundException) {
            Logger.i(tag = TAG) { "404 (not shared / missing) url=$url" }
            return null
        }
        return decrypted(response, keyHeader)
    }

    private suspend fun fetch(url: String, token: String, maxBytes: Long?): ByteApiResponse {
        val response = requestBytes(maxBytes) { httpClient.get(url) { bearerAuth(token) } }
        // 404 becomes NotFoundException, which the cache memoises so a followed post with no
        // server-side thumbnail stops being re-requested on every scroll past it.
        throwForFailure(response)
        return response
    }

    private suspend fun decrypted(response: ByteApiResponse, keyHeader: KeyHeader): BytesResponse? {
        if (response.status == 404) return null
        val bytes = driveFileHttpProvider.decryptBytes(keyHeader, response.headers, response.bytes)
        return BytesResponse(bytes = bytes, contentType = response.contentType)
    }

    // The peer segment keeps these off the own-drive keys: a followed post has no local fileId, and
    // two identities can publish the same gtid on same-aliased channel drives.
    private fun peerCacheKey(kind: String, vararg parts: Any): String =
        (listOf(kind, "peer") + parts.toList()).joinToString(":")

    companion object { private const val TAG = "PeerFileByGtid" }
}
