package id.homebase.agent

import id.homebase.api.client.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
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
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.services.ReplyContext
import id.homebase.chat.services.ReplyPreview
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import id.homebase.chat.widget.replyQuoteMediaPayloads
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant
import kotlin.uuid.Uuid

const val REPLY_QUOTE_CODEPOINTS = 80
const val REQUEST_TIMEOUT_MS = 30_000L

fun HttpClient.withRequestTimeout(ms: Long): HttpClient = config {
    install(HttpTimeout) {
        requestTimeoutMillis = ms
        socketTimeoutMillis = ms
    }
}

class NotLoggedInException(profile: String) :
    Exception("not logged in for profile '$profile', run: chat-agent login --profile $profile")

class Session(val identity: OdinId, val credentials: CredentialsManager, val http: HttpClient)

suspend fun openSession(profile: String): Session {
    val stored = CredentialStorage.getCredentials() ?: throw NotLoggedInException(profile)
    val credentials = CredentialsManager()
    credentials.setActiveCredentials(
        ApiCredentials.create(stored.identity, stored.clientAuthToken, stored.sharedSecret)
    )
    return Session(stored.identity, credentials, HttpClientProvider.create().withRequestTimeout(REQUEST_TIMEOUT_MS))
}

class ChatMsg(
    val id: Uuid,
    val conversationId: Uuid,
    val author: OdinId?,
    val text: String,
    val userDate: Long,
    val previewThumbnail: EmbeddedThumb? = null,
    val payloads: List<PayloadDescriptor>? = null,
    val dataType: Int? = null,
    val rawContent: String? = null,
    val sender: OdinId? = null,
) {
    val replyContext: JsonElement?
        get() = (MessageContentParser.parse(dataType, rawContent) as? MessageContent.Event)?.descriptor
            ?.let { ReplyContext.event(it.startUtcMs) }
}

fun ChatMsg.toReplyPreview() = ReplyPreview(
    replyUniqueId = id,
    authorOdinId = author?.domainName ?: "null",
    message = text.trim().truncateToCodePoints(REPLY_QUOTE_CODEPOINTS),
    previewThumbnail = previewThumbnail.takeIf { payloads.replyQuoteMediaPayloads().firstOrNull()?.isVisualMedia() == true },
    context = replyContext,
)

suspend fun fetchMessages(session: Session, conversationId: Uuid, limit: Int, beforeMs: Long? = null): List<ChatMsg> =
    fetchMessages(session, listOf(conversationId), limit, beforeMs)

suspend fun fetchMessages(session: Session, conversationIds: List<Uuid>?, limit: Int, beforeMs: Long? = null): List<ChatMsg> {
    if (conversationIds?.isEmpty() == true) return emptyList()
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
                previewThumbnail = metadata.appData.previewThumbnail,
                payloads = metadata.payloads,
                dataType = metadata.appData.dataType,
                rawContent = metadata.appData.content,
                sender = metadata.senderOdinId,
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
