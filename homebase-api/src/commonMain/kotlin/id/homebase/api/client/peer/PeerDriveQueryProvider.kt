package id.homebase.api.client.peer

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import io.ktor.client.HttpClient
import kotlin.uuid.Uuid

/**
 * Asks the user's own server to check whether a file exists on a connected peer.
 * The peer-to-peer call is performed server-side; this client only talks to
 * the user's own host (creds.domain) over the standard V2 OwnerOrApp API.
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
        val response = encryptedGet(
            url = apiUrl(creds.domain, "/peer/$peer/drives/$driveId/files/by-uid/$uniqueId/exists"),
            token = creds.accessToken,
            secret = creds.secret,
        )
        throwForFailure(response)
        return deserialize(response.body)
    }

    suspend fun fileExistsByGlobalTransitId(
        peer: OdinId,
        driveId: Uuid,
        globalTransitId: Uuid,
    ): FileExistsOnPeerResponse {
        val creds = requireCreds()
        val response = encryptedGet(
            url = apiUrl(creds.domain, "/peer/$peer/drives/$driveId/files/by-gtid/$globalTransitId/exists"),
            token = creds.accessToken,
            secret = creds.secret,
        )
        throwForFailure(response)
        return deserialize(response.body)
    }
}
