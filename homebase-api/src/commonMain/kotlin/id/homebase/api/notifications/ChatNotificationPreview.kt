package id.homebase.api.notifications

import co.touchlab.kermit.Logger
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
 * The text of chat message [messageId] in [conversationId], fetched from the identity and
 * decrypted on-device, for a push handler that runs without the app graph (the iOS Notification
 * Service Extension).
 *
 * Null for anything that isn't readable plain text — a typed kind, status message, media-only
 * or deleted message, a header that failed to decrypt, a network failure — so the caller keeps
 * the sender's placeholder, which for typed kinds is already their privacy-aware label. Never
 * throws except for cancellation.
 */
suspend fun fetchChatNotificationPreview(
    credentials: ApiCredentials,
    conversationId: String,
    messageId: String,
): String? {
    val httpClient = createPlatformHttpClient()
    try {
        return fetchChatNotificationPreview(httpClient, credentials, conversationId, messageId)
    } finally {
        httpClient.close()
    }
}

internal suspend fun fetchChatNotificationPreview(
    httpClient: HttpClient,
    credentials: ApiCredentials,
    conversationId: String,
    messageId: String,
): String? {
    val conversation = Uuid.parseOrNull(conversationId) ?: return null
    val message = Uuid.parseOrNull(messageId) ?: return null
    // A Moments post push carries the moment id in both fields; it is not a chat message.
    if (conversation == message) return null

    val file = try {
        val credentialsManager = CredentialsManager().apply { setActiveCredentials(credentials) }
        DriveFileHeaderReader(httpClient, credentialsManager)
            .getFileHeaderByUid(SystemDriveConstants.chatDrive.alias, message)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(tag = "ChatNotificationPreview") { "header fetch failed: ${e.message}" }
        null
    } ?: return null

    return chatMessagePreview(file, conversation)
}

internal fun chatMessagePreview(file: HomebaseFile, conversationId: Uuid): String? {
    val appData = file.fileMetadata.appData
    if (appData.fileType != CHAT_MESSAGE_FILE_TYPE) return null
    if ((appData.dataType ?: PLAIN_MESSAGE_DATA_TYPE) != PLAIN_MESSAGE_DATA_TYPE) return null
    if (appData.groupId != conversationId || file.isSoftDeleted()) return null

    val content = appData.content?.takeUnless { it == DECRYPTION_FAILURE_APP_DATA } ?: return null
    val message = try {
        OdinSystemSerializer.json.parseToJsonElement(content).jsonObject["message"]
    } catch (_: Exception) {
        null
    }
    val text = when (message) {
        is JsonPrimitive -> message.content.takeIf { message.isString }
        is JsonArray -> try {
            getPlainTextFromRichText(OdinSystemSerializer.json.decodeFromJsonElement<RichText>(message))
        } catch (_: Exception) {
            null
        }
        else -> null
    }
    return text?.takeIf { it.isNotBlank() }
}
