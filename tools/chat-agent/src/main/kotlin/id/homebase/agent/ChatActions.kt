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
import id.homebase.api.common.OdinId
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.groodle.GroodleDescriptor
import id.homebase.chat.groodle.GroodleVote
import id.homebase.chat.poll.PollDescriptor
import id.homebase.chat.poll.PollVote
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.decodeReactionCode
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.viewonce.ViewOnceSignal
import id.homebase.api.sync.database.TombstoneRetention
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

private const val REACTION_PAGES = 20

private suspend fun ownReactionCodes(provider: DriveFileGroupReactionProvider, fileId: Uuid, self: OdinId): List<String> {
    val codes = ArrayList<String>()
    var cursor: Int? = null
    var pages = 0
    do {
        val page = provider.listReactions(chatDrive, fileId, cursor, 100)
        page.reactions.filter { it.odinId == self }.mapNotNullTo(codes) { decodeReactionCode(it.reactionContent) }
        cursor = page.cursor
    } while (cursor != null && ++pages < REACTION_PAGES)
    return codes
}

private suspend fun ownReactions(session: Session, allowlist: Allowlist, conversationId: Uuid, message: ChatMsg): OwnReactions {
    val fileId = fileIdOf(message)
    val provider = DriveFileGroupReactionProvider(session.http, session.credentials)
    val recipients = conversationRecipients(conversationFor(session, allowlist, conversationId), session.identity)
    return object : OwnReactions {
        override suspend fun codes() = ownReactionCodes(provider, fileId, session.identity)
        override suspend fun add(code: String) { provider.addReaction(chatDrive, fileId, reactionJson(code), recipients) }
        override suspend fun remove(code: String) { provider.deleteReaction(chatDrive, fileId, reactionJson(code), recipients) }
    }
}

interface OwnReactions {
    suspend fun codes(): List<String>
    suspend fun add(code: String)
    suspend fun remove(code: String)
}

suspend fun castPollVote(reactions: OwnReactions, poll: PollDescriptor, option: Int): Boolean {
    val count = poll.options.size
    val own = PollVote.ownVotes(reactions.codes(), count)
    if (option in own && (poll.allowMultiple || own == setOf(option))) return false
    val change = PollVote.change(option, own, count, poll.allowMultiple)
    change.remove.filter { PollVote.optionOf(it) in own }.forEach { reactions.remove(it) }
    change.add.filter { PollVote.optionOf(it) !in own }.forEach { reactions.add(it) }
    return true
}

suspend fun voteOnPoll(session: Session, allowlist: Allowlist, conversationId: Uuid, message: ChatMsg, option: Int, poll: PollDescriptor): Boolean {
    allowlist.requireSend(conversationId)
    return castPollVote(ownReactions(session, allowlist, conversationId, message), poll, option)
}

suspend fun castGroodleVote(reactions: OwnReactions, groodle: GroodleDescriptor, slot: Int, choice: GroodleVote.Choice): Boolean {
    val own = reactions.codes()
    val current = GroodleVote.myVotes(own, groodle.slots.size, groodle.allowMaybe)[slot]
    if (current == choice) return false
    val change = GroodleVote.change(slot, current, choice, groodle.allowMaybe)
    change.remove.filter { it in own }.forEach { reactions.remove(it) }
    change.add.forEach { reactions.add(it) }
    return true
}

suspend fun voteOnGroodle(session: Session, allowlist: Allowlist, conversationId: Uuid, message: ChatMsg, slot: Int, choice: GroodleVote.Choice, groodle: GroodleDescriptor): Boolean {
    allowlist.requireSend(conversationId)
    return castGroodleVote(ownReactions(session, allowlist, conversationId, message), groodle, slot, choice)
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

// reactToMessage rejects '_' codes, and this one is not an emoji
suspend fun addViewOnceSignal(session: Session, allowlist: Allowlist, conversationId: Uuid, message: ChatMsg) {
    allowlist.requireSend(conversationId)
    val recipients = conversationRecipients(conversationFor(session, allowlist, conversationId), session.identity)
    DriveFileGroupReactionProvider(session.http, session.credentials).addReaction(chatDrive, fileIdOf(message), reactionJson(ViewOnceSignal.OPENED_CODE), recipients)
}

suspend fun stripOwnViewOnceCopy(session: Session, conversationId: Uuid, message: ChatMsg) {
    val file = messageFile(session, conversationId, message.id)
    val metadata = file.fileMetadata
    val payloadKeys = metadata.payloads.orEmpty().map { it.key }
    if (payloadKeys.isEmpty()) return
    val appData = metadata.appData
    val keyHeader = KeyHeader(KeyHeader.newRandom16().iv, file.keyHeader.aesKey)
    val update = UploadFileMetadata(
        allowDistribution = false,
        isEncrypted = file.serverFileIsEncrypted,
        accessControlList = file.serverMetadata.accessControlList,
        versionTag = metadata.versionTag ?: error("message has no version tag"),
        appData = UploadAppFileMetaData(
            uniqueId = message.id,
            tags = appData.tags,
            groupId = appData.groupId,
            fileType = appData.fileType,
            dataType = appData.dataType,
            userDate = appData.userDate,
            archivalStatus = appData.archivalStatus,
            content = TombstoneRetention.viewOnceKindOnly(appData.content),
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
                recipients = emptyList(),
                manifest = UpdateManifest.build(toDeletePayloads = payloadKeys.map(::PayloadDeleteKey)),
            ),
            metadata = update.encryptContent(keyHeader),
        ),
    )
    requireNotNull(result) { "view-once strip was rejected (the message changed meanwhile)" }
}
