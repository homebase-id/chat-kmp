package id.homebase.agent

import id.homebase.api.HomebaseProtocol
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.EmbeddedThumb
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
import id.homebase.chat.services.ReplyPreview
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.video.VideoPayloadProcessor
import id.homebase.chat.services.builder.LinkPreviewPayloadBuilder
import id.homebase.upload.PayloadBundleEncryptionService
import java.io.File
import java.nio.file.Files
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonPrimitive

fun buildMessageContent(text: String, replyPreview: ReplyPreview? = null, allowBlank: Boolean = false): String {
    require(allowBlank || text.isNotBlank()) { "message text is empty" }
    val content = OdinSystemSerializer.serialize(
        MessageAppData(replyPreview = replyPreview, message = JsonPrimitive(text), deliveryStatus = ChatDeliveryStatus.Sent.value, version = 1)
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
    conversationId: Uuid,
    messageId: Uuid,
    text: String,
    nowMs: Long,
    keyHeader: KeyHeader,
    distribute: Boolean,
    replyPreview: ReplyPreview? = null,
    previewThumbnail: EmbeddedThumb? = null,
    allowBlank: Boolean = false,
): UploadFileMetadata {
    return UploadFileMetadata(
            allowDistribution = distribute,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = messageId,
                groupId = conversationId,
                fileType = ChatProtocol.MessageFileType,
                dataType = 0,
                userDate = nowMs,
                content = buildMessageContent(text, replyPreview, allowBlank),
                previewThumbnail = previewThumbnail,
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

suspend fun outgoingBundle(text: String, files: List<OutFile>, previews: LinkPreviewSource?): StagedBundle? {
    val fileOps = JvmFileOperationsProvider()
    if (files.isNotEmpty()) return buildOutgoingBundle(files, fileOps)
    val url = previews?.let { firstUrl(text) } ?: return null
    val preview = try {
        previews.preview(url)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        System.err.println("link preview skipped: ${e.message}")
        null
    } ?: return null
    val dir = Files.createTempDirectory("chat-agent-out").toFile()
    val bundle = try {
        LinkPreviewPayloadBuilder.build(preview, fileOps)
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        LinkPreviewPayloadBuilder.build(preview.copy(imageUrl = null, imageWidth = null, imageHeight = null), fileOps)
    }
    return StagedBundle(bundle, dir)
}

private fun payloadEncryptor(fileOps: JvmFileOperationsProvider) =
    PayloadBundleEncryptionService(fileOps, VideoPayloadProcessor(fileOps), EventBus())

suspend fun sendToConversation(
    session: Session,
    allowlist: Allowlist,
    conversationId: Uuid,
    text: String,
    replyPreview: ReplyPreview? = null,
    files: List<OutFile> = emptyList(),
    previews: LinkPreviewSource? = null,
): Uuid {
    allowlist.requireSend(conversationId)
    require(files.size <= MAX_OUT_FILES) { "at most $MAX_OUT_FILES files per message" }
    val conversation = if (conversationId == ChatProtocol.ConversationWithYourselfId) {
        noteToSelf(session.identity)
    } else {
        allowlist.info(conversationId) ?: error("conversation $conversationId not found among this identity's conversations")
    }
    val recipients = conversationRecipients(conversation, session.identity)
    require(recipients.isNotEmpty() || conversation.id == ChatProtocol.ConversationWithYourselfId) {
        "no recipients resolved for conversation ${conversation.id}"
    }
    val messageId = Uuid.random()
    val text = allowlist.disclosure(conversation.id, text, session.identity)
    val keyHeader = KeyHeader.newRandom16()
    val staged = outgoingBundle(text, files, previews)
    try {
        val fileOps = JvmFileOperationsProvider()
        val encrypted = staged?.let { payloadEncryptor(fileOps).encryptBundle(messageId, it.bundle, keyHeader.aesKey, CoroutineScope(currentCoroutineContext())) }
        val metadata = buildMessageMetadata(
            conversation.id, messageId, text, Clock.System.now().toEpochMilliseconds(), keyHeader,
            distribute = recipients.isNotEmpty(),
            replyPreview = replyPreview,
            previewThumbnail = staged?.bundle?.previewThumbs?.minByOrNull { it.pixelWidth },
            allowBlank = files.isNotEmpty(),
        )
        DriveUploadProvider(session.http, session.credentials, fileOps)
            .uploadFile(
                UploadFileRequest(
                    driveId = SystemDriveConstants.chatDrive.alias,
                    keyHeader = keyHeader,
                    metadata = metadata,
                    transitOptions = conversationTransitOptions(conversation.id, messageId, recipients, conversation.title),
                    payloads = encrypted?.payloads.orEmpty(),
                    thumbnails = encrypted?.thumbnails.orEmpty(),
                )
            )
    } finally {
        staged?.let { s ->
            s.bundle.payloads.forEach { runCatching { File(it.filePath).delete() } }
            s.cleanup()
        }
    }
    return messageId
}

suspend fun send(profile: String, text: String, conversation: Uuid? = null, file: String? = null) {
    val session = openSession(profile)
    val config = loadConfig(profile, session.identity)
    val allowlist = config.allowlist
    val id = conversation ?: ChatProtocol.ConversationWithYourselfId
    refreshAllowlist(session, allowlist)
    allowlist.requireSend(id)
    val files = file?.let { listOf(loadOutFile(File(it).canonicalFile)) }.orEmpty()
    println("sent ${sendToConversation(session, allowlist, id, text, files = files, previews = if (config.linkPreviews) serverLinkPreviews(session) else null)}")
}
