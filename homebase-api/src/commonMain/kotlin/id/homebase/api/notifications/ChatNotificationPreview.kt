package id.homebase.api.notifications

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.createPlatformHttpClient
import id.homebase.api.client.drives.DECRYPTION_FAILURE_APP_DATA
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.DriveFileHeaderReader
import id.homebase.api.client.drives.files.RichText
import id.homebase.api.client.drives.files.getPlainTextFromRichText
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

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
 * or deleted message, a header that failed to decrypt, a network failure — so the caller keeps
 * the sender's placeholder, which for typed kinds is already their privacy-aware label. Never
 * throws except for cancellation.
 */
suspend fun fetchChatNotificationPreview(
    credentials: ApiCredentials,
    conversationId: String,
    messageId: String,
    encryptedPreview: String? = null,
): ChatNotificationPreview = createPlatformHttpClient().use { client ->
    fetchChatNotificationPreview(client, credentials, conversationId, messageId, encryptedPreview)
}

/**
 * [encryptedPreview] is the header the identity sealed into the push (see [openPushPreview]).
 * It is read first because the push usually lands while the message is still in the transit
 * inbox, where the by-uid fetch answers 404; the fetch is the fallback.
 */
internal suspend fun fetchChatNotificationPreview(
    httpClient: HttpClient,
    credentials: ApiCredentials,
    conversationId: String,
    messageId: String,
    encryptedPreview: String? = null,
): ChatNotificationPreview {
    val conversation = Uuid.parseOrNull(conversationId)
        ?: return ChatNotificationPreview.missed("typeId is not a uuid")
    val message = Uuid.parseOrNull(messageId)
        ?: return ChatNotificationPreview.missed("tagId is not a uuid")
    // A Moments post push carries the moment id in both fields; it is not a chat message.
    if (conversation == message) return ChatNotificationPreview.missed("moments push")

    var pushMiss = "no push preview"
    if (encryptedPreview != null) {
        val opened = try {
            openPushPreview(encryptedPreview, pushPreviewKey(credentials.sharedSecret))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null.also { pushMiss = "push preview did not open ${e.causeChain()}" }
        }
        if (opened != null) {
            pushPreviewResult(opened, conversation, message)?.let { return it }
            pushMiss = "push preview has no content"
        }
    }

    return fetchedPreview(httpClient, credentials, conversation, message).let { fetched ->
        fetched.missReason?.let { ChatNotificationPreview.missed("$pushMiss; $it") } ?: fetched
    }
}

private suspend fun fetchedPreview(
    httpClient: HttpClient,
    credentials: ApiCredentials,
    conversation: Uuid,
    message: Uuid,
): ChatNotificationPreview {
    val file = try {
        val credentialsManager = CredentialsManager().apply { setActiveCredentials(credentials) }
        DriveFileHeaderReader(httpClient, credentialsManager)
            .getFileHeaderByUid(SystemDriveConstants.chatDrive.alias, message)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return ChatNotificationPreview.missed("header fetch threw ${e.causeChain()}")
    } ?: return ChatNotificationPreview.missed("header not found (404)")

    return chatMessagePreview(file, conversation)
}

// Class names only: an exception message can quote the decrypted header.
private fun Throwable.causeChain(): String =
    generateSequence(this) { it.cause }.take(4).joinToString(" <- ") { it::class.simpleName ?: "?" }

/** Null when the preview can't decide (content dropped to fit the push), so the header is fetched. */
private fun pushPreviewResult(preview: PushPreview, conversationId: Uuid, messageId: Uuid): ChatNotificationPreview? {
    val miss = when {
        Uuid.parseOrNull(preview.uniqueId.orEmpty()) != messageId -> "push preview is for another file"
        preview.fileType != CHAT_MESSAGE_FILE_TYPE -> "fileType ${preview.fileType}"
        preview.dataType != PLAIN_MESSAGE_DATA_TYPE -> "dataType ${preview.dataType}"
        Uuid.parseOrNull(preview.groupId.orEmpty()) != conversationId -> "groupId ${preview.groupId} is not the push typeId"
        preview.content.isNullOrEmpty() -> return null
        else -> null
    }
    if (miss != null) return ChatNotificationPreview.missed(miss)

    val result = messageText(preview.content!!, "from the push")
    return if (preview.contentTruncated && result.text != null) ChatNotificationPreview.shown("${result.text}…") else result
}

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

    return messageText(appData.content!!, "serverFileIsEncrypted=${file.serverFileIsEncrypted}")
}

private fun messageText(content: String, source: String): ChatNotificationPreview {
    val message = try {
        OdinSystemSerializer.json.parseToJsonElement(content).jsonObject["message"]
    } catch (_: Exception) {
        return ChatNotificationPreview.missed("content is not a json object ($source)")
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
