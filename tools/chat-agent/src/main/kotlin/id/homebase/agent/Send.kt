package id.homebase.agent

import id.homebase.api.HomebaseProtocol
import id.homebase.api.client.HttpClientProvider
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.PushNotificationOptions
import id.homebase.api.client.drives.upload.SendContents
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ChatDeliveryStatus
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.MessageAppData
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonPrimitive

fun buildMessageContent(text: String): String {
    require(text.isNotBlank()) { "message text is empty" }
    val content = OdinSystemSerializer.serialize(
        MessageAppData(message = JsonPrimitive(text), deliveryStatus = ChatDeliveryStatus.Sent.value, version = 1)
    )
    val size = content.encodeToByteArray().size
    require(size <= HomebaseProtocol.MaxHeaderContentBytes) {
        "message too large: $size bytes serialized, limit is ${HomebaseProtocol.MaxHeaderContentBytes}"
    }
    return content
}

suspend fun buildSelfMessageMetadata(
    allowlist: Allowlist,
    conversationId: Uuid,
    messageId: Uuid,
    text: String,
    nowMs: Long,
    keyHeader: KeyHeader,
): UploadFileMetadata {
    allowlist.requireConversation(conversationId)
    return UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = messageId,
                groupId = conversationId,
                fileType = ChatProtocol.MessageFileType,
                dataType = 0,
                userDate = nowMs,
                content = buildMessageContent(text),
            ),
        ).encryptContent(keyHeader)
}

fun selfTransitOptions(conversationId: Uuid, messageId: Uuid) = TransitOptions(
    recipients = emptyList(),
    sendContents = SendContents.All,
    useAppNotification = false,
    appNotificationOptions = PushNotificationOptions(
        appId = ChatProtocol.ChatAppId.toString(),
        typeId = conversationId.toString(),
        tagId = messageId.toString(),
        silent = false,
        unEncryptedMessage = "You have a new message",
    ),
)

suspend fun send(profile: String, text: String) {
    val session = openSession(profile)
    val allowlist = Allowlist.default(session.identity)
    val messageId = Uuid.random()
    val conversationId = ChatProtocol.ConversationWithYourselfId
    val keyHeader = KeyHeader.newRandom16()
    val metadata = buildSelfMessageMetadata(
        allowlist, conversationId, messageId, text, Clock.System.now().toEpochMilliseconds(), keyHeader
    )
    val result = DriveUploadProvider(HttpClientProvider.create(), session.credentials, JvmFileOperationsProvider())
        .uploadFile(
            UploadFileRequest(
                driveId = SystemDriveConstants.chatDrive.alias,
                keyHeader = keyHeader,
                metadata = metadata,
                transitOptions = selfTransitOptions(conversationId, messageId),
            )
        )
    println("sent $messageId${result?.let { " (file ${it.fileId})" }.orEmpty()}")
}
