package id.homebase.api.client.peer

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.BytesResponse
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.common.OdinId
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import kotlin.uuid.Uuid

/**
 * Reads a payload (or its thumbnail) of a file that lives on a **followed identity's** drive,
 * addressed by [globalTransitId]. The user's own host ([requireCreds]`.domain`) brokers the read
 * to the peer server-side and re-encrypts the response under the caller's shared secret, so the
 * decode/decrypt path is identical to a local read (it reuses [DriveFileProvider.decryptBytes],
 * which returns plaintext bytes untouched when the server marks the payload `payloadencrypted=false`
 * — the public-feed case).
 *
 * Why a new provider rather than [TemporalDriveReadProvider]: that one is the *time-boxed
 * emergency* API (needs a `ConditionalTemporalRead` circle grant, clamps to a recent window,
 * notifies the author's owner, and keys by **fileId**). Feed posts need none of that and the
 * Feed-drive reference only carries a **globalTransitId**, never the author's fileId — hence the
 * by-gtid route here, extending the `/peer/.../files/by-gtid/{gtid}/exists` route
 * [PeerDriveQueryProvider] already uses. Mirrors dotyoucore-js
 * `get{Payload,Thumb}BytesOverPeerByGlobalTransitId`.
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
        return fetchAndDecrypt(url)
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
            "getThumb: GET peer=${peer.domainName} drive=$driveId gtid=$globalTransitId " +
                "key=$payloadKey ${width}x$height"
        }
        return fetchAndDecrypt(url)
    }

    private suspend fun fetchAndDecrypt(url: String): BytesResponse? {
        val creds = requireCreds()
        val response = requestBytes { httpClient.get(url) { bearerAuth(creds.accessToken) } }
        if (response.status == 404) return null
        throwForFailure(response)
        val decrypted = driveFileProvider.decryptBytes(response.headers, response.bytes)
        return BytesResponse(bytes = decrypted, contentType = response.contentType)
    }

    companion object { private const val TAG = "PeerFileByGtid" }
}
