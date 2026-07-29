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

/**
 * Reads a payload (or its thumbnail) of a file on a **followed identity's** drive, addressed by
 * [globalTransitId]. Feed posts from people you follow land on your local feed drive as *header-only*
 * references — the media bytes stay on the author's drive — so displaying their images needs a read
 * over peer, which this provides.
 *
 * The user's own host ([requireCreds]`.domain`) brokers the request to the peer server-side and
 * re-encrypts the payload under the caller's shared secret, so the decode/decrypt path is identical
 * to a local read: [DriveFileProvider.decryptBytes] recovers the key header from the
 * `sharedsecretencryptedheader64` response header (and returns plaintext untouched when the server
 * marks `payloadencrypted=false` — the public-feed case).
 *
 * Routes are the non-temporal twin of [id.homebase.api.client.peer.temporal.TemporalDriveReadProvider],
 * under odin-core UnifiedV2's `PeerByGtid` base (`V2DrivePeerQueryByGtidController`):
 * - payload:   `GET /peer/{odinId}/drives/{driveId}/files/by-gtid/{gtid}/payload/{payloadKey}`
 * - thumbnail: `GET .../by-gtid/{gtid}/payload/{payloadKey}/thumb/{width}/{height}`
 *
 * Full payload reads are size-guarded at [PayloadSizePolicy.RENDER_LIMIT_BYTES] (#845), same as the
 * local read: `HomebaseImageLoader.fetchFullPayloadUncached` catches the
 * [id.homebase.api.client.PayloadTooLargeException] and keeps the already-rendered thumbnail instead
 * of buffering a followed identity's oversized photo into RAM. Thumbnails stay uncapped, matching
 * `DriveFileHttpProvider.getThumbBytesRawNetwork`.
 *
 * TODO: unlike the local read this bypasses
 * [id.homebase.api.client.drives.cache.DriveFileProviderCached], so every followed-post
 * thumbnail is re-downloaded. Routing it through needs peer/gtid-aware cache keys AND a cache entry
 * format that persists `sharedsecretencryptedheader64` — the cached bytes stay encrypted and, unlike
 * the local path, no caller-supplied [id.homebase.api.client.KeyHeader] exists to decrypt them on a
 * hit. [id.homebase.api.client.peer.temporal.TemporalDriveReadProvider] has the same gap.
 *
 * Both endpoints carry no shared secret on the wire (`NoSharedSecretOnRequest/Response`); the remote
 * resolves the drive by **alias only** (Type is ignored), so [driveId] is the channel drive's alias.
 * The predecessor WIP used the retired V1 `/transit/query/{payload,thumb}_byglobaltransitid` shape,
 * which 404s under this client's `/api/v2` prefix — see reference_over_peer_media_v2_route.
 */
class PeerFileByGlobalTransitProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
    private val driveFileProvider: DriveFileProvider,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    /** Full payload bytes of [globalTransitId]'s [payloadKey] on [peer]'s [driveId]. Null on 404. */
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

    /** A server thumbnail ([width]x[height]) of [globalTransitId]'s [payloadKey]. Null on 404. */
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
