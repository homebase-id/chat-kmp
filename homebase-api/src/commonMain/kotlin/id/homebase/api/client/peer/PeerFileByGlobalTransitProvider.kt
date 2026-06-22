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
 * `/api/apps/v1/transit/query/{payload,thumb}_byglobaltransitid` (PeerQueryControllerBase) with the
 * peer + drive (alias+type) + gtid as query params.
 *
 * KNOWN LIMITATION (verified against the odin backend): these `*_byglobaltransitid` endpoints live
 * ONLY under the classic App/Owner/Guest API v1 surface, which authenticates a classic app
 * ClientAuthToken (cookie). chat-kmp logs in via the unified v2 API and sends a v2 bearer token, so
 * these v1 routes reject it with 401. The unified v2 peer surface exposes payload/thumb over peer
 * ONLY by **fileId** (`/api/v2/peer/{odinId}/drives/{driveId}/files/{fileId}/payload/{key}[/thumb]`,
 * V2DrivePeerFileReadonlyController) — never by gtid (v2 by-gtid is `exists` only). So the v2-native
 * path is: resolve gtid→author-fileId via an over-peer query-batch (FileQueryParams.globalTransitId
 * filter, supported), then fetch payload/thumb by that fileId. TODO: implement that resolve step;
 * until then followed-post media falls back to the embedded preview thumbnail (no regression).
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
        val url = transitQueryUrl(creds.domain, "payload_byglobaltransitid", query)
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
            "&globalTransitId=$globalTransitId&payloadKey=$payloadKey" +
            "&width=$width&height=$height&directMatchOnly=false"
        val url = transitQueryUrl(creds.domain, "thumb_byglobaltransitid", query)
        Logger.i(tag = TAG) {
            "getThumb: GET peer=${peer.domainName} alias=$driveAlias gtid=$globalTransitId " +
                "key=$payloadKey ${width}x$height"
        }
        return fetchAndDecrypt(url)
    }

    // PeerQueryControllerBase routes the *_byglobaltransitid endpoints under the App API v1
    // transit/query path. The unified /api/v2 peer surface only exposes `exists` by gtid (no
    // payload/thumb), so [apiUrl]'s /api/v2 base 404s here — build the App-API-v1 URL directly.
    private fun transitQueryUrl(domain: OdinId, endpoint: String, query: String): String =
        "https://$domain/api/apps/v1/transit/query/$endpoint?$query"

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
