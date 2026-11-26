package id.homebase.api.client.drives.files.reactions

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
private data class PeerReactionRequest(val reaction: String)

@Serializable
private data class PeerReactionsByIdentityRequest(val identity: String)

/**
 * Reactions on a file another identity hosts, by the global transit id both know it by, sent through this identity's
 * server (odin-core `V2DrivePeerReactionController`). The owner's server lets a reaction in only through a share row
 * with React on the file.
 */
class PeerReactionProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    suspend fun addReaction(owner: OdinId, driveId: Uuid, globalTransitId: Uuid, reaction: String) {
        require(reaction.isNotBlank())
        post(owner, driveId, globalTransitId, "add", OdinSystemSerializer.serialize(PeerReactionRequest(reaction)))
    }

    suspend fun deleteReaction(owner: OdinId, driveId: Uuid, globalTransitId: Uuid, reaction: String) {
        require(reaction.isNotBlank())
        post(owner, driveId, globalTransitId, "delete", OdinSystemSerializer.serialize(PeerReactionRequest(reaction)))
    }

    suspend fun getReactionSummary(owner: OdinId, driveId: Uuid, globalTransitId: Uuid): GetGroupReactionCountsResponse =
        deserialize(post(owner, driveId, globalTransitId, "summary", "{}"))

    suspend fun listReactionsByIdentity(owner: OdinId, driveId: Uuid, globalTransitId: Uuid, identity: OdinId): List<String> =
        deserialize(post(owner, driveId, globalTransitId, "listbyidentity", OdinSystemSerializer.serialize(PeerReactionsByIdentityRequest(identity.domainName))))

    private suspend fun post(owner: OdinId, driveId: Uuid, globalTransitId: Uuid, action: String, body: String): String {
        val creds = requireCreds()
        val response = encryptedPostJson(
            url = apiUrl(creds.domain, "/peer/${owner.domainName}/drives/$driveId/files/by-gtid/$globalTransitId/reactions/$action"),
            token = creds.accessToken,
            jsonBody = body,
            secret = creds.secret,
        )
        throwForFailure(response)
        return response.body
    }
}
