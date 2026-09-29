package id.homebase.agent

import id.homebase.api.client.HttpClientProvider
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.youauth.CredentialStorage
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.MessageAppData
import kotlin.time.Instant

class NotLoggedInException(profile: String) :
    Exception("not logged in for profile '$profile', run: chat-agent login --profile $profile")

suspend fun read(profile: String, limit: Int) {
    val stored = CredentialStorage.getCredentials() ?: throw NotLoggedInException(profile)
    val credentials = CredentialsManager()
    credentials.setActiveCredentials(
        ApiCredentials.create(stored.identity, stored.clientAuthToken, stored.sharedSecret)
    )
    val allowlist = Allowlist.default(stored.identity)
    val conversationId = ChatProtocol.ConversationWithYourselfId
    allowlist.requireConversation(conversationId)

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
                    ),
                ),
            )

    response.searchResults
        .filter { allowlist.allowsAuthor(it.fileMetadata.originalAuthor) }
        .sortedBy { it.fileMetadata.appData.userDate ?: it.fileMetadata.created.milliseconds }
        .forEach { file ->
            val metadata = file.fileMetadata
            val text = runCatching {
                OdinSystemSerializer.deserialize<MessageAppData>(metadata.appData.content.orEmpty()).getMessage()
            }.getOrDefault("[unreadable message]")
            val at = Instant.fromEpochMilliseconds(metadata.appData.userDate ?: metadata.created.milliseconds)
            println("$at ${metadata.originalAuthor}: $text")
        }
}
