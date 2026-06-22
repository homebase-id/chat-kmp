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
 * Reads a payload (or its thumbnail) of a file on a **followed identity's** drive, addressed by
 * [globalTransitId]. The user's own host ([requireCreds]`.domain`) brokers the transit query to the
 * peer server-side and re-encrypts the response under the caller's shared secret, so the
 * decode/decrypt path is identical to a local read (reuses [DriveFileProvider.decryptBytes], which
 * returns plaintext bytes untouched when the server marks `payloadencrypted=false` — the public-feed
 * case).
 *
 * Routes mirror dotyoucore-js `get{Payload,Thumb}BytesOverPeerByGlobalTransitId`:
 * `/transit/query/{payload,thumb}_byglobaltransitid` with the peer + drive (alias+type) + gtid as
 * query params (NOT the `/peer/.../files/...` path form, which only exposes `exists` by gtid).
 */
class PeerFileByGlobalTransitProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
    private val driveFileProvider: DriveFileProvider,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    /** Full payload bytes of [globalTransitId]'s [payloadKey] on [peer]'s drive. Null on 404. */
    suspend fun getPayloadOverPeerByGlobalTransitId(
        peer: OdinId,
        driveAlias: Uuid,
        driveType: Uuid,
        globalTransitId: Uuid,
        payloadKey: String,
    ): BytesResponse? {
        require(payloadKey.isNotBlank()) { "payloadKey must be defined" }
        val creds = requireCreds()
        val query = "odinId=$peer&alias=$driveAlias&type=$driveType" +
            "&globalTransitId=$globalTransitId&key=$payloadKey"
        val url = apiUrl(creds.domain, "/transit/query/payload_byglobaltransitid?$query")
        Logger.i(tag = TAG) {
            "getPayload: GET peer=${peer.domainName} alias=$driveAlias gtid=$globalTransitId key=$payloadKey"
        }
        return fetchAndDecrypt(url)
    }

    /** A server thumbnail ([width]x[height]) of [globalTransitId]'s [payloadKey]. Null on 404. */
    suspend fun getThumbOverPeerByGlobalTransitId(
        peer: OdinId,
        driveAlias: Uuid,
        driveType: Uuid,
        globalTransitId: Uuid,
        payloadKey: String,
        width: Int,
        height: Int,
    ): BytesResponse? {
        require(payloadKey.isNotBlank()) { "payloadKey must be defined" }
        require(width > 0 && height > 0) { "width/height must be positive" }
        val creds = requireCreds()
        val query = "odinId=$peer&alias=$driveAlias&type=$driveType" +
            "&globalTransitId=$globalTransitId&payloadKey=$payloadKey&width=$width&height=$height"
        val url = apiUrl(creds.domain, "/transit/query/thumb_byglobaltransitid?$query")
        Logger.i(tag = TAG) {
            "getThumb: GET peer=${peer.domainName} alias=$driveAlias gtid=$globalTransitId " +
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
