package id.homebase.agent

import id.homebase.api.client.HttpClientProvider
import io.ktor.client.HttpClient
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.youauth.CredentialStorage
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.MessageAppData
import kotlin.time.Instant
import kotlin.uuid.Uuid

class NotLoggedInException(profile: String) :
    Exception("not logged in for profile '$profile', run: chat-agent login --profile $profile")

class Session(val identity: OdinId, val credentials: CredentialsManager, val http: HttpClient)

suspend fun openSession(profile: String): Session {
    val stored = CredentialStorage.getCredentials() ?: throw NotLoggedInException(profile)
    val credentials = CredentialsManager()
    credentials.setActiveCredentials(
        ApiCredentials.create(stored.identity, stored.clientAuthToken, stored.sharedSecret)
    )
    return Session(stored.identity, credentials, HttpClientProvider.create())
}

class ChatMsg(
    val id: Uuid,
    val conversationId: Uuid,
    val author: OdinId?,
    val text: String,
    val userDate: Long,
)

suspend fun fetchMessages(session: Session, conversationId: Uuid, limit: Int, beforeMs: Long? = null): List<ChatMsg> =
    fetchMessages(session, listOf(conversationId), limit, beforeMs)

suspend fun fetchMessages(session: Session, conversationIds: List<Uuid>, limit: Int, beforeMs: Long? = null): List<ChatMsg> {
    if (conversationIds.isEmpty()) return emptyList()
    // The owner's own files carry neither originalAuthor nor senderOdinId.
    val owner = session.credentials.getActiveDomain()
    val response =
        DriveQueryProvider(session.http, session.credentials)
            .queryBatch(
                driveId = SystemDriveConstants.chatDrive.alias,
                request = QueryBatchRequest(
                    queryParams = FileQueryParams(
                        fileType = listOf(ChatProtocol.MessageFileType),
                        groupId = conversationIds,
                        fileState = listOf(FileState.Active),
                    ),
                    resultOptionsRequest = QueryBatchResultOptionsRequest(
                        cursorState = beforeMs?.let { QueryBatchCursor.fromStartPoint(UnixTimeUtc(it)).toJson() },
                        maxRecords = limit,
                        includeMetadataHeader = true,
                        ordering = QueryBatchSortOrder.NewestFirst,
                        sorting = QueryBatchSortField.UserDate,
                    ),
                ),
            )
    return response.searchResults
        .mapNotNull { file ->
            val metadata = file.fileMetadata
            val conversationId = metadata.appData.groupId ?: return@mapNotNull null
            val text = runCatching {
                OdinSystemSerializer.deserialize<MessageAppData>(metadata.appData.content.orEmpty()).getMessage()
            }.getOrDefault("[unreadable message]")
            ChatMsg(
                id = metadata.appData.uniqueId ?: file.fileId,
                conversationId = conversationId,
                author = metadata.originalAuthor ?: metadata.senderOdinId ?: owner,
                text = text,
                userDate = metadata.appData.userDate ?: metadata.created.milliseconds,
            )
        }
        .filter { beforeMs == null || it.userDate < beforeMs }
        .sortedBy { it.userDate }
}

suspend fun read(profile: String, limit: Int, conversation: Uuid? = null) {
    val session = openSession(profile)
    val allowlist = loadConfig(profile, session.identity).allowlist
    val conversationId = conversation ?: ChatProtocol.ConversationWithYourselfId
    refreshAllowlist(session, allowlist)
    allowlist.requireConversation(conversationId)

    fetchMessages(session, conversationId, limit)
        .filter { allowlist.allowsAuthor(it.author, conversationId) }
        .forEach { println("${Instant.fromEpochMilliseconds(it.userDate)} ${it.author}: ${it.text}") }
}
