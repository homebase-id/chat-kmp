package id.homebase.api.client.peer

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.PayloadSizePolicy
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.BytesResponse
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.common.OdinId
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import kotlin.uuid.Uuid

// Feed posts from people you follow land on the local feed drive as header-only references — the media bytes
// stay on the author's drive — so displaying their images needs this read over peer. The user's own host
// brokers the request and re-encrypts under the caller's shared secret, so the decode path is identical to a
// local read.
//
// Full payload reads are size-guarded at PayloadSizePolicy.RENDER_LIMIT_BYTES so a followed identity's
// oversized photo can't be buffered into RAM; thumbnails stay uncapped, matching the local read.
//
// The remote resolves the drive by ALIAS only (Type is ignored), so [driveId] is the channel drive's alias.
//
// TODO: unlike the local read this bypasses DriveFileProviderCached, so every followed-post thumbnail is
// re-downloaded. Routing it through needs peer/gtid-aware cache keys AND a cache entry format that persists
// `sharedsecretencryptedheader64` — the cached bytes stay encrypted and no caller-supplied KeyHeader exists to
// decrypt them on a hit. TemporalDriveReadProvider has the same gap.
class PeerFileByGlobalTransitProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
    private val driveFileProvider: DriveFileProvider,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    /** Null on 404. */
    suspend fun getPayloadOverPeerByGlobalTransitId(
        peer: OdinId,
        driveId: Uuid,
        globalTransitId: Uuid,
        payloadKey: String,
    ): BytesResponse? {
        require(payloadKey.isNotBlank()) { "payloadKey must be defined" }
        val creds = requireCreds()
        val url = apiUrl(
            creds.domain,
            "/peer/$peer/drives/$driveId/files/by-gtid/$globalTransitId/payload/$payloadKey",
        )
        Logger.i(tag = TAG) {
            "getPayload: GET peer=${peer.domainName} drive=$driveId gtid=$globalTransitId key=$payloadKey"
        }
        return fetchAndDecrypt(url, creds.accessToken, maxBytes = PayloadSizePolicy.RENDER_LIMIT_BYTES)
    }

    /** Null on 404. */
    suspend fun getThumbOverPeerByGlobalTransitId(
        peer: OdinId,
        driveId: Uuid,
        globalTransitId: Uuid,
        payloadKey: String,
        width: Int,
        height: Int,
    ): BytesResponse? {
        require(payloadKey.isNotBlank()) { "payloadKey must be defined" }
        require(width > 0 && height > 0) { "width/height must be positive" }
        val creds = requireCreds()
        val url = apiUrl(
            creds.domain,
            "/peer/$peer/drives/$driveId/files/by-gtid/$globalTransitId/payload/$payloadKey/thumb/$width/$height",
        )
        Logger.i(tag = TAG) {
            "getThumb: GET peer=${peer.domainName} drive=$driveId gtid=$globalTransitId key=$payloadKey ${width}x$height"
        }
        return fetchAndDecrypt(url, creds.accessToken)
    }

    private suspend fun fetchAndDecrypt(
        url: String,
        token: String,
        maxBytes: Long? = null,
    ): BytesResponse? {
        val response = requestBytes(maxBytes) { httpClient.get(url) { bearerAuth(token) } }
        if (response.status == 404) {
            Logger.i(tag = TAG) { "404 (not shared / missing) url=$url" }
            return null
        }
        throwForFailure(response)
        val decrypted = driveFileProvider.decryptBytes(response.headers, response.bytes)
        return BytesResponse(bytes = decrypted, contentType = response.contentType)
    }

    companion object { private const val TAG = "PeerFileByGtid" }
}
