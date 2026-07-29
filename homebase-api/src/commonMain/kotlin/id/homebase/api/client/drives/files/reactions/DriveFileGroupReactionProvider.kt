package id.homebase.api.client.drives.files.reactions


import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.ValidationUtil
import id.homebase.api.client.websockets.InternalDriveFileId
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

// ==================== REQUEST / RESPONSE MODELS ====================

@Serializable
data class ReactionTransitOptions(
    val recipients: List<OdinId>
)

@Serializable
data class AddGroupReactionRequest(
    val reaction: String,
    val transitOptions: ReactionTransitOptions
)


@Serializable
data class DeleteGroupReactionRequest(
    val reaction: String,
    val transitOptions: ReactionTransitOptions
)

@Serializable
data class GetGroupReactionsRequest(
    val cursor: String? = null,
    val maxRecords: Int? = null
)

@Serializable
data class GetGroupReactionsByIdentityRequest(
    val identity: OdinId
)

@Serializable
data class GroupReactionItem(
    val reactionContent: String,
    val odinId: OdinId,
    val fileId: InternalDriveFileId,
    val created: Long
)

/**
 * Response of `GET /drives/{driveId}/files/{fileId}/group-reactions`.
 *
 * [cursor] is the server's `GetReactionsResponse.Cursor`, an `int?` (a rowid) —
 * NOT a string. It was typed `String?` here, which made the strict
 * (`isLenient = false`) decoder throw `JsonDecodingException` on any response
 * that actually carried one, i.e. whenever a file has more reactions than the
 * page size. That exception took the WHOLE roster with it at every call site.
 */
@Serializable
data class GetGroupReactionsResponse(
    val reactions: List<GroupReactionItem>,
    val cursor: Int? = null
)

@Serializable
data class GetGroupReactionCountsResponse(
    val reactions: Map<String, Int>,
    val total: Int
)

// ==================== PROVIDER ====================

@OptIn(ExperimentalEncodingApi::class)
class DriveFileGroupReactionProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager
) : OdinApiProviderBase(httpClient, credentialsManager) {

    companion object {
        private const val TAG = "DriveFileGroupReactionProvider"
    }

    // -------------------- ADD --------------------

    suspend fun addReaction(
        driveId: Uuid,
        fileId: Uuid,
        reaction: String,
        recipients: List<OdinId>
    ) {
        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")
        require(reaction.isNotBlank())

        val creds = requireCreds()
        val endpoint = "/drives/$driveId/files/$fileId/group-reactions"

        // "{\"emoji\":\"\uD83D\uDE2E\"}"
        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(
                AddGroupReactionRequest(
                    reaction = reaction,
                    transitOptions = ReactionTransitOptions(
                        recipients = recipients
                    )
                )
            ),
            secret = creds.secret
        )

        throwForFailure(response)
    }

    // -------------------- DELETE --------------------

    suspend fun deleteReaction(
        driveId: Uuid,
        fileId: Uuid,
        reaction: String,
        recipients: List<OdinId>
    ) {
        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")
        require(reaction.isNotBlank())

        val creds = requireCreds()
        val endpoint = "/drives/$driveId/files/$fileId/group-reactions"

        val response = encryptedDelete(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(
                DeleteGroupReactionRequest(
                    reaction = reaction,
                    transitOptions = ReactionTransitOptions(
                        recipients = recipients
                    )
                )
            ),
            secret = creds.secret
        )

        throwForFailure(response)
    }

    suspend fun toggleReaction(
        driveId: Uuid,
        fileId: Uuid,
        reaction: String,
        recipients: List<OdinId>
    ): ToggleReactionResult {

        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")

        val creds = requireCreds()
        val endpoint = "/drives/$driveId/files/$fileId/group-reactions/toggle"

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(
                ToggleReactionRequest(
                    reaction = reaction,
                    transitOptions = ReactionTransitOptions(
                        recipients = recipients
                    )
                )
            ),
            secret = creds.secret
        )

        throwForFailure(response)
        return deserialize(response.body)
    }

    // -------------------- LIST --------------------

    suspend fun listReactions(
        driveId: Uuid,
        fileId: Uuid,
        cursor: Int? = null,
        maxRecords: Int? = null
    ): GetGroupReactionsResponse {

        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")

        val creds = requireCreds()
        val endpoint = "/drives/$driveId/files/$fileId/group-reactions"

        val queryString = buildString {
            cursor?.let { append("cursor=$it") }
            maxRecords?.let {
                if (isNotEmpty()) append("&")
                append("maxRecords=$it")
            }
        }

        val response = encryptedGet(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = queryString.ifBlank { null }
        )

        throwForFailure(response)
        return deserialize(response.body)
    }

    // -------------------- SUMMARY --------------------

    suspend fun getReactionSummary(
        driveId: Uuid,
        fileId: Uuid
    ): GetGroupReactionCountsResponse {

        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")

        val creds = requireCreds()
        val endpoint = "/drives/$driveId/files/$fileId/group-reactions/summary"

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(
                GetGroupReactionsRequest()
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

        val creds = requireCreds()
        val endpoint = "/drives/$driveId/files/$fileId/group-reactions/by-identity"

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(
                GetGroupReactionsByIdentityRequest(identity)
            ),
            secret = creds.secret
        )

        throwForFailure(response)
        return deserialize(response.body)
    }
}


