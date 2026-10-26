package id.homebase.api.notifications

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.createPlatformHttpClient
import id.homebase.api.client.drives.DECRYPTION_FAILURE_APP_DATA
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.RichText
import id.homebase.api.client.drives.files.getPlainTextFromRichText
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Tag of a Moments comment push; a post push repeats the moment id in both fields. */
const val MOMENT_COMMENT_TAG_SENTINEL = "00000000-0000-4000-8000-0000c0117e57"

fun isMomentsPush(typeId: String, tagId: String): Boolean {
    if (typeId.isBlank()) return false
    val tag = Uuid.parseOrNull(tagId)
    return (tag != null && tag == Uuid.parseOrNull(MOMENT_COMMENT_TAG_SENTINEL)) || typeId == tagId
}

private const val CHAT_MESSAGE_FILE_TYPE = 7878
private const val PLAIN_MESSAGE_DATA_TYPE = 0

/**
 * Either the text to show, or why the sender's placeholder stays. [missReason] never carries
 * message content, so the extension can log it unredacted.
 */
data class ChatNotificationPreview(val text: String?, val missReason: String?) {
    internal companion object {
        fun shown(text: String) = ChatNotificationPreview(text, null)
        fun missed(reason: String) = ChatNotificationPreview(null, reason)
    }
}

/**
 * The text of chat message [messageId] in [conversationId], fetched from the identity and
 * decrypted on-device, for a push handler that runs without the app graph (the iOS Notification
 * Service Extension).
 *
 * No text for anything that isn't readable plain text — a typed kind, status message, media-only
 * or deleted message, a header that failed to decrypt, a network failure, or no answer within
 * [PREVIEW_DEADLINE] — so the caller keeps the sender's placeholder, which for typed kinds is
 * already their privacy-aware label. Never throws except for cancellation.
 */
suspend fun fetchChatNotificationPreview(
    credentials: ApiCredentials,
    conversationId: String,
    messageId: String,
): ChatNotificationPreview = createPlatformHttpClient().use { client ->
    fetchChatNotificationPreview(client, credentials, conversationId, messageId)
}

internal val QUERY_TIMEOUT = 8.seconds

// The NSE gets ~30 s in all; this leaves room for its badge read (2 s) and avatar fetch (5 s).
internal val PREVIEW_DEADLINE = 18.seconds

internal suspend fun fetchChatNotificationPreview(
    httpClient: HttpClient,
    credentials: ApiCredentials,
    conversationId: String,
    messageId: String,
    deadline: Duration = PREVIEW_DEADLINE,
    queryTimeout: Duration = QUERY_TIMEOUT,
): ChatNotificationPreview {
    val conversation = Uuid.parseOrNull(conversationId)
        ?: return ChatNotificationPreview.missed("typeId is not a uuid")
    val message = Uuid.parseOrNull(messageId)
        ?: return ChatNotificationPreview.missed("tagId is not a uuid")
    if (isMomentsPush(conversationId, messageId)) return ChatNotificationPreview.missed("moments push")

    return withTimeoutOrNull(deadline) {
        try {
            fetchedPreview(httpClient, credentials, conversation, message, queryTimeout)
        } catch (e: StepFailed) {
            ChatNotificationPreview.missed(e.reason)
        }
    } ?: ChatNotificationPreview.missed("timed out after $deadline")
}

// QueryBatch drains the drive's transit inbox before it queries; the by-uid header read doesn't.
private suspend fun fetchedPreview(
    httpClient: HttpClient,
    credentials: ApiCredentials,
    conversation: Uuid,
    message: Uuid,
    queryTimeout: Duration,
): ChatNotificationPreview {
    val credentialsManager = CredentialsManager().apply { setActiveCredentials(credentials) }
    val request = QueryBatchRequest(
        FileQueryParams(clientUniqueIdAtLeastOne = listOf(message)),
        QueryBatchResultOptionsRequest(maxRecords = 1, includeMetadataHeader = true),
    )
    val response = step("query", queryTimeout) {
        DriveQueryProvider(httpClient, credentialsManager).queryBatch(SystemDriveConstants.chatDrive.alias, request)
    }
    if (response.invalidDrive) return ChatNotificationPreview.missed("query answered invalidDrive")
    val file = response.searchResults.firstOrNull()
        ?: return ChatNotificationPreview.missed("query returned 0 results")
    return chatMessagePreview(file, conversation)
}

private class StepFailed(val reason: String) : Exception(reason)

private suspend fun <T> step(name: String, timeout: Duration, block: suspend () -> T): T {
    val outcome = withTimeoutOrNull(timeout) {
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    } ?: throw StepFailed("$name timed out after $timeout")
    return outcome.getOrElse { throw StepFailed("$name threw ${it.causeChain()}") }
}

// Class names only: an exception message can quote the decrypted header.
private fun Throwable.causeChain(): String =
    generateSequence(this) { it.cause }.take(4).joinToString(" <- ") { it::class.simpleName ?: "?" }

internal fun chatMessagePreview(file: HomebaseFile, conversationId: Uuid): ChatNotificationPreview {
    val appData = file.fileMetadata.appData
    val dataType = appData.dataType ?: PLAIN_MESSAGE_DATA_TYPE
    val miss = when {
        appData.fileType != CHAT_MESSAGE_FILE_TYPE -> "fileType ${appData.fileType}"
        dataType != PLAIN_MESSAGE_DATA_TYPE -> "dataType $dataType"
        appData.groupId != conversationId -> "groupId ${appData.groupId} is not the push typeId"
        file.isSoftDeleted() -> "deleted (${file.fileState}, ${appData.archivalStatus})"
        appData.content.isNullOrEmpty() -> "no content (serverFileIsEncrypted=${file.serverFileIsEncrypted})"
        appData.content == DECRYPTION_FAILURE_APP_DATA -> "content failed to decrypt"
        else -> null
    }
    if (miss != null) return ChatNotificationPreview.missed(miss)

    val message = try {
        OdinSystemSerializer.json.parseToJsonElement(appData.content!!).jsonObject["message"]
    } catch (_: Exception) {
        return ChatNotificationPreview.missed(
            "content is not a json object (serverFileIsEncrypted=${file.serverFileIsEncrypted})"
        )
    }
    val text = when (message) {
        is JsonPrimitive -> message.content.takeIf { message.isString }
        is JsonArray -> try {
            getPlainTextFromRichText(OdinSystemSerializer.json.decodeFromJsonElement<RichText>(message))
        } catch (_: Exception) {
            return ChatNotificationPreview.missed("rich text did not parse")
        }
        else -> null
    }
    return text?.takeIf { it.isNotBlank() }?.let(ChatNotificationPreview::shown)
        ?: ChatNotificationPreview.missed("no message text")
}
