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
import id.homebase.api.common.OdinId
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

fun conversationRecipients(conversation: ConversationInfo, self: OdinId): List<OdinId> =
    if (conversation.id == ChatProtocol.ConversationWithYourselfId) emptyList()
    else conversation.members.filter { it != self }

suspend fun buildMessageMetadata(
    allowlist: Allowlist,
    conversationId: Uuid,
    messageId: Uuid,
    text: String,
    nowMs: Long,
    keyHeader: KeyHeader,
    distribute: Boolean,
): UploadFileMetadata {
    allowlist.requireSend(conversationId)
    return UploadFileMetadata(
            allowDistribution = distribute,
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

fun conversationTransitOptions(
    conversationId: Uuid,
    messageId: Uuid,
    recipients: List<OdinId>,
    groupName: String?,
) = TransitOptions(
    recipients = recipients,
    sendContents = SendContents.All,
    useAppNotification = recipients.isNotEmpty(),
    appNotificationOptions = PushNotificationOptions(
        appId = ChatProtocol.ChatAppId.toString(),
        typeId = conversationId.toString(),
        tagId = messageId.toString(),
        silent = false,
        unEncryptedMessage = if (recipients.size > 1) {
            "You have a new message in ${groupName?.takeIf { it.isNotBlank() } ?: "a group chat"}"
        } else {
            "You have a new message"
        },
    ),
)

fun noteToSelf(self: OdinId) = ConversationInfo(ChatProtocol.ConversationWithYourselfId, NOTE_TO_SELF_TITLE, listOf(self))

suspend fun resolveConversation(session: Session, allowlist: Allowlist, id: Uuid): ConversationInfo {
    allowlist.requireSend(id)
    if (id == ChatProtocol.ConversationWithYourselfId) return noteToSelf(session.identity)
    allowlist.info(id) ?: allowlist.learn(discoverConversations(session.credentials))
    return allowlist.info(id) ?: error("conversation $id not found among this identity's conversations")
}

suspend fun sendToConversation(session: Session, allowlist: Allowlist, conversation: ConversationInfo, text: String): Uuid {
    val recipients = conversationRecipients(conversation, session.identity)
    require(recipients.isNotEmpty() || conversation.id == ChatProtocol.ConversationWithYourselfId) {
        "no recipients resolved for conversation ${conversation.id}"
    }
    val messageId = Uuid.random()
    val keyHeader = KeyHeader.newRandom16()
    val metadata = buildMessageMetadata(
        allowlist, conversation.id, messageId, text, Clock.System.now().toEpochMilliseconds(), keyHeader,
        distribute = recipients.isNotEmpty(),
    )
    DriveUploadProvider(HttpClientProvider.create(), session.credentials, JvmFileOperationsProvider())
        .uploadFile(
            UploadFileRequest(
                driveId = SystemDriveConstants.chatDrive.alias,
                keyHeader = keyHeader,
                metadata = metadata,
                transitOptions = conversationTransitOptions(conversation.id, messageId, recipients, conversation.title),
            )
        )
    return messageId
}

suspend fun sendToConversation(session: Session, allowlist: Allowlist, conversationId: Uuid, text: String): Uuid =
    sendToConversation(session, allowlist, resolveConversation(session, allowlist, conversationId), text)

suspend fun send(profile: String, text: String, conversation: Uuid? = null) {
    val session = openSession(profile)
    val allowlist = loadConfig(profile, session.identity).allowlist
    val id = conversation ?: ChatProtocol.ConversationWithYourselfId
    require(id == ChatProtocol.ConversationWithYourselfId || allowlist.groupSend) { "this profile may only send to note-to-self" }
    refreshAllowlist(session.credentials, allowlist)
    allowlist.requireSend(id)
    println("sent ${sendToConversation(session, allowlist, id, text)}")
}
