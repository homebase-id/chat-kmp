package id.homebase.agent

import id.homebase.api.client.HttpClientProvider
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
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.youauth.CredentialStorage
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.MessageAppData
import kotlin.time.Instant
import kotlin.uuid.Uuid

class NotLoggedInException(profile: String) :
    Exception("not logged in for profile '$profile', run: chat-agent login --profile $profile")

class Session(val identity: OdinId, val credentials: CredentialsManager)

suspend fun openSession(profile: String): Session {
    val stored = CredentialStorage.getCredentials() ?: throw NotLoggedInException(profile)
    val credentials = CredentialsManager()
    credentials.setActiveCredentials(
        ApiCredentials.create(stored.identity, stored.clientAuthToken, stored.sharedSecret)
    )
    return Session(stored.identity, credentials)
}

class ChatMsg(
    val id: Uuid,
    val conversationId: Uuid,
    val author: OdinId?,
    val text: String,
    val userDate: Long,
)

suspend fun fetchMessages(credentials: CredentialsManager, conversationId: Uuid, limit: Int): List<ChatMsg> {
    // The owner's own files carry neither originalAuthor nor senderOdinId.
    val owner = credentials.getActiveDomain()
    val response =
        DriveQueryProvider(HttpClientProvider.create(), credentials)
            .queryBatch(
                driveId = SystemDriveConstants.chatDrive.alias,
                request = QueryBatchRequest(
                    queryParams = FileQueryParams(
                        fileType = listOf(ChatProtocol.MessageFileType),
                        groupId = listOf(conversationId),
                        fileState = listOf(FileState.Active),
                    ),
                    resultOptionsRequest = QueryBatchResultOptionsRequest(
                        maxRecords = limit,
                        includeMetadataHeader = true,
                        ordering = QueryBatchSortOrder.NewestFirst,
                        sorting = QueryBatchSortField.UserDate,
                    ),
                ),
            )
    return response.searchResults
        .map { file ->
            val metadata = file.fileMetadata
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
        .sortedBy { it.userDate }
}

suspend fun read(profile: String, limit: Int, conversation: Uuid? = null) {
    val session = openSession(profile)
    val allowlist = loadConfig(profile, session.identity).allowlist
    val conversationId = conversation ?: ChatProtocol.ConversationWithYourselfId
    refreshAllowlist(session.credentials, allowlist)
    allowlist.requireConversation(conversationId)

    fetchMessages(session.credentials, conversationId, limit)
        .filter { allowlist.allowsAuthor(it.author, conversationId) }
        .forEach { println("${Instant.fromEpochMilliseconds(it.userDate)} ${it.author}: ${it.text}") }
}
