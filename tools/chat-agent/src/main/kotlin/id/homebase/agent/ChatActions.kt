package id.homebase.agent

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.PayloadDeleteKey
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.MessageAppData
import kotlin.uuid.Uuid

fun reactionJson(emoji: String): String = OdinSystemSerializer.serialize(ReactionContent(emoji = emoji))

private val chatDrive get() = SystemDriveConstants.chatDrive.alias

private fun fileIdOf(message: ChatMsg) = message.fileId ?: error("message ${message.id} has no file id")

suspend fun reactToMessage(session: Session, allowlist: Allowlist, conversationId: Uuid, message: ChatMsg, emoji: String, add: Boolean) {
    allowlist.requireSend(conversationId)
    require(isValidReaction(emoji)) { "emoji must be a single emoji" }
    val recipients = conversationRecipients(conversationFor(session, allowlist, conversationId), session.identity)
    val provider = DriveFileGroupReactionProvider(session.http, session.credentials)
    if (add) provider.addReaction(chatDrive, fileIdOf(message), reactionJson(emoji), recipients)
    else provider.deleteReaction(chatDrive, fileIdOf(message), reactionJson(emoji), recipients)
}

suspend fun deleteOwnMessage(session: Session, allowlist: Allowlist, conversationId: Uuid, message: ChatMsg) {
    allowlist.requireSend(conversationId)
    val recipients = conversationRecipients(conversationFor(session, allowlist, conversationId), session.identity)
    DriveFileHttpProvider(session.http, session.credentials).deleteFiles(chatDrive, listOf(fileIdOf(message)), recipients)
}

private suspend fun messageFile(session: Session, conversationId: Uuid, messageId: Uuid): HomebaseFile =
    session.query.queryBatch(
        driveId = chatDrive,
        request = QueryBatchRequest(
            queryParams = FileQueryParams(
                fileType = listOf(ChatProtocol.MessageFileType),
                groupId = listOf(conversationId),
                clientUniqueIdAtLeastOne = listOf(messageId),
                fileState = listOf(FileState.Active),
            ),
            resultOptionsRequest = QueryBatchResultOptionsRequest(maxRecords = 1, includeMetadataHeader = true),
        ),
    ).searchResults.firstOrNull() ?: error("message $messageId not found")

// short plain text only: typed kinds are immutable and a long message needs its overflow payload re-encrypted
suspend fun editOwnMessage(session: Session, allowlist: Allowlist, conversationId: Uuid, message: ChatMsg, text: String) {
    allowlist.requireSend(conversationId)
    require(message.dataType in setOf(null, 0) && !message.isLong && message.payloads.isNullOrEmpty()) { "only short text messages can be edited" }
    val file = messageFile(session, conversationId, message.id)
    val metadata = file.fileMetadata
    require(metadata.senderOdinId == null || metadata.senderOdinId == session.identity) { "you can only edit your own messages" }
    val versionTag = metadata.versionTag ?: error("message has no version tag")
    val previous = OdinSystemSerializer.deserialize<MessageAppData>(metadata.appData.content.orEmpty())
    val newText = allowlist.disclosure(conversationId, text, session.identity)
    val built = buildMessage(newText, previous.replyPreview, edited = true)
    require(built.payloadJson == null) { "edited text is too long, send a new message instead" }
    val conversation = conversationFor(session, allowlist, conversationId)
    val recipients = conversationRecipients(conversation, session.identity)
    val keyHeader = KeyHeader(KeyHeader.newRandom16().iv, file.keyHeader.aesKey)
    val update = UploadFileMetadata(
        allowDistribution = recipients.isNotEmpty(),
        isEncrypted = true,
        versionTag = versionTag,
        appData = UploadAppFileMetaData(
            uniqueId = message.id,
            groupId = conversationId,
            fileType = ChatProtocol.MessageFileType,
            dataType = 0,
            userDate = metadata.appData.userDate ?: message.userDate,
            content = built.header,
            previewThumbnail = metadata.appData.previewThumbnail,
        ),
    )
    val result = DriveUploadProvider(session.http, session.credentials, JvmFileOperationsProvider()).updateFileByUniqueId(
        UpdateFileByUniqueIdRequest(
            driveId = chatDrive,
            uniqueId = message.id,
            keyHeader = keyHeader,
            instructions = FileUpdateInstructionSet(
                transferIv = KeyHeader.newRandom16().iv,
                locale = UpdateLocale.Local,
                recipients = recipients,
                manifest = UpdateManifest.build(toDeletePayloads = listOf(PayloadDeleteKey(ChatProtocol.DefaultPayloadKey))),
            ),
            metadata = update.encryptContent(keyHeader),
        ),
    )
    requireNotNull(result) { "edit was rejected (the message changed meanwhile)" }
}
