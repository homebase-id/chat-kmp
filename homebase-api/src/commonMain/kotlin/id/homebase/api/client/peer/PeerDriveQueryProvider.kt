package id.homebase.api.client.peer

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import io.ktor.client.HttpClient
import kotlin.uuid.Uuid

/**
 * Asks the user's own server to check whether a file exists on a connected peer.
 * The peer-to-peer call is performed server-side; this client only talks to
 * the user's own host (creds.domain) over the standard V2 OwnerOrApp API.
 *
 * Every call logs request + raw response under the `PeerDriveQuery` tag so
 * heal flows (and anything else asking "does the peer hold this file?") can
 * be audited against the wire-level answer without re-running the call.
 */
class PeerDriveQueryProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    suspend fun fileExistsByUniqueId(
        peer: OdinId,
        driveId: Uuid,
        uniqueId: Uuid,
    ): FileExistsOnPeerResponse {
        val creds = requireCreds()
        val url = apiUrl(creds.domain, "/peer/$peer/drives/$driveId/files/by-uid/$uniqueId/exists")
        Logger.i(tag = TAG) {
            "fileExistsByUniqueId: GET peer=${peer.domainName} drive=$driveId uid=$uniqueId"
        }
        val response = encryptedGet(
            url = url,
            token = creds.accessToken,
            secret = creds.secret,
        )
        try {
            throwForFailure(response)
        } catch (e: Exception) {
            Logger.w(throwable = e, tag = TAG) {
                "fileExistsByUniqueId: FAIL peer=${peer.domainName} drive=$driveId uid=$uniqueId status=${response.status}"
            }
            throw e
        }
        val body: FileExistsOnPeerResponse = deserialize(response.body)
        Logger.i(tag = TAG) {
            "fileExistsByUniqueId: OK   peer=${peer.domainName} drive=$driveId uid=$uniqueId → exists=${body.exists} versionTag=${body.versionTag}"
        }
        return body
    }

    suspend fun fileExistsByGlobalTransitId(
        peer: OdinId,
        driveId: Uuid,
        globalTransitId: Uuid,
    ): FileExistsOnPeerResponse {
        val creds = requireCreds()
        val url = apiUrl(creds.domain, "/peer/$peer/drives/$driveId/files/by-gtid/$globalTransitId/exists")
        Logger.i(tag = TAG) {
            "fileExistsByGlobalTransitId: GET peer=${peer.domainName} drive=$driveId gtid=$globalTransitId"
        }
        val response = encryptedGet(
            url = url,
            token = creds.accessToken,
            secret = creds.secret,
        )
        try {
            throwForFailure(response)
        } catch (e: Exception) {
            Logger.w(throwable = e, tag = TAG) {
                "fileExistsByGlobalTransitId: FAIL peer=${peer.domainName} drive=$driveId gtid=$globalTransitId status=${response.status}"
            }
            throw e
        }
        val body: FileExistsOnPeerResponse = deserialize(response.body)
        Logger.i(tag = TAG) {
            "fileExistsByGlobalTransitId: OK   peer=${peer.domainName} drive=$driveId gtid=$globalTransitId → exists=${body.exists} versionTag=${body.versionTag}"
        }
        return body
    }

    companion object { private const val TAG = "PeerDriveQuery" }
}
