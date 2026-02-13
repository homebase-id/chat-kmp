package id.homebase.api.client.drives.files.reactions

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.ValidationUtil
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import id.homebase.api.common.OdinId

// ==================== REQUEST / RESPONSE MODELS ====================

@Serializable
data class AddReactionRequest(
    val reaction: String
)

@Serializable
data class DeleteReactionRequest(
    val reaction: String
)

@Serializable
data class GetReactionsRequest(
    val cursor: String? = null,
    val maxRecords: Int? = null
)

@Serializable
data class GetReactionsByIdentityRequest(
    val identity: OdinId
)

@Serializable
data class ReactionItem(
    val reaction: String,
    val identity: OdinId,
    val createdUtc: String
)

@Serializable
data class GetReactionsResponse(
    val reactions: List<ReactionItem>,
    val cursor: String? = null
)

@Serializable
data class GetReactionCountsResponse(
    val counts: Map<String, Int>
)

// ==================== PROVIDER ====================

@OptIn(ExperimentalEncodingApi::class)
public class DriveFileReactionProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager
) : OdinApiProviderBase(httpClient, credentialsManager) {

    companion object {
        private const val TAG = "DriveFileReactionProvider"
    }

    // -------------------- ADD --------------------

    suspend fun addReaction(
        driveId: Uuid,
        fileId: Uuid,
        reaction: String
    ) {
        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")
        require(reaction.isNotBlank()) { "reaction must not be blank" }

        val creds = requireCreds()
        val endpoint = "/drives/$driveId/files/$fileId/reactions/add"

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(
                AddReactionRequest(
                    reaction = "{\"emoji\":\"\uD83D\uDE2E\"}"
                )
            ),
            secret = creds.secret
        )

        throwForFailure(response)
    }

    // -------------------- DELETE ONE --------------------

    suspend fun deleteReaction(
        driveId: Uuid,
        fileId: Uuid,
        reaction: String
    ) {
        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")
        require(reaction.isNotBlank()) { "reaction must not be blank" }

        val creds = requireCreds()
        val endpoint = "/drives/$driveId/files/$fileId/reactions/delete"

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(
                DeleteReactionRequest(
                    reaction
                )
            ),
            secret = creds.secret
        )

        throwForFailure(response)
    }

    // -------------------- DELETE ALL --------------------

    suspend fun deleteAllReactions(
        driveId: Uuid,
        fileId: Uuid
    ) {
        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")

        val creds = requireCreds()
        val endpoint = "/drives/$driveId/files/$fileId/reactions/deleteall"

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(
                DeleteReactionRequest(
                    reaction = ""
                )
            ),
            secret = creds.secret
        )

        throwForFailure(response)
    }

    // -------------------- LIST --------------------

    suspend fun listReactions(
        driveId: Uuid,
        fileId: Uuid,
        cursor: String? = null,
        maxRecords: Int? = null
    ): GetReactionsResponse {

        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")

        val creds = requireCreds()
        val endpoint = "/drives/$driveId/files/$fileId/reactions/list"

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(
                GetReactionsRequest(
                    cursor,
                    maxRecords
                )
            ),
            secret = creds.secret
        )

        throwForFailure(response)

        return deserialize(response.body)
    }

    // -------------------- SUMMARY --------------------

    suspend fun getReactionSummary(
        driveId: Uuid,
        fileId: Uuid
    ): GetReactionCountsResponse {

        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")

        val creds = requireCreds()
        val endpoint = "/drives/$driveId/files/$fileId/reactions/summary"

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(
                GetReactionsRequest()
            ),
            secret = creds.secret
        )

        throwForFailure(response)

        return deserialize(response.body)
    }

    // -------------------- LIST BY IDENTITY --------------------

    suspend fun listReactionsByIdentity(
        driveId: Uuid,
        fileId: Uuid,
        identity: OdinId
    ): List<String> {

        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")
        // require(identity.isNotBlank()) { "identity must not be blank" }

        val creds = requireCreds()
        val endpoint = "/drives/$driveId/files/$fileId/reactions/listbyidentity"

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(
                GetReactionsByIdentityRequest(
                    identity
                )
            ),
            secret = creds.secret
        )

        throwForFailure(response)

        return deserialize(response.body)
    }
}
