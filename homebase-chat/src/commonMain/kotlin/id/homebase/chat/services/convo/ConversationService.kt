package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.ConnectionIntroductionProvider
import id.homebase.api.client.connections.IntroductionGroup
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.PayloadBundleEncryptionService
import id.homebase.chat.services.XorIdUtil
import id.homebase.core.config.chatTargetDrive
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope

class ConversationService(
    private val credentialsManager: CredentialsManager,
    private val conversationRepository: ConversationRepository,
    private val payloadBundleEncryptionService: PayloadBundleEncryptionService,
    private val contactService: ContactService,
    private val introductionProvider: ConnectionIntroductionProvider,
    private val conversationUpdater: ConversationUpdater,
    private val chatMessageSenderService: ChatMessageSenderService,
    private val scope: CoroutineScope
) {

    private val chatDrive = chatTargetDrive.alias

    suspend fun createConversation(
        recipients: List<OdinId>,
        title: String?,
        payloadBundle: PayloadBundle?
    ): Uuid {

        val domain = credentialsManager.requireActiveDomain()
        val keyHeader = KeyHeader.newRandom16()
        val isGroup = recipients.size > 1

        val newConversationId: Uuid =
            if (isGroup) {
                Uuid.random()
            } else {
                XorIdUtil.getNewXorId(domain.domainName, recipients.first().domainName)
            }

        val existingConversation = conversationRepository.getConversation(newConversationId)
        if (existingConversation != null) {
            return newConversationId
        }

        val normalizedRecipients = normalizeRecipients(recipients, domain)

        val content = buildConversationContent(title, normalizedRecipients)

        val encryptedBundle =
            payloadBundleEncryptionService.encryptBundle(
                newConversationId,
                payloadBundle,
                keyHeader.aesKey,
                scope
            )

        val previewThumb = selectPreviewThumb(encryptedBundle.previewThumbs)

        val metadata =
            buildConversationMetadata(
                newConversationId,
                content,
                previewThumb
            )

        val request =
            UploadFileRequest(
                driveId = chatDrive,
                keyHeader = keyHeader,
                metadata = metadata.encryptContent(keyHeader),
                transitOptions =
                    TransitOptions(recipients = recipients, useAppNotification = false),
                payloads = encryptedBundle.payloads,
                thumbnails = encryptedBundle.thumbnails
            )

        conversationRepository.createConversationFile(newConversationId, request)

        if (isGroup) {
            trySendIntroductions(recipients, "$domain has added you to a group chat")

            // this wont work here because we've got a timing issue with sync
//            chatMessageSenderService.sendSystemMessage(
//                messageUniqueId = Uuid.random(),
//                conversationId = newConversationId,
//                messageText = "$domain started this group titled $title"
//            )
        }

        return newConversationId
    }

    suspend fun updateGroupMembers(
        conversationId: Uuid,
        add: List<OdinId> = emptyList(),
        remove: List<OdinId> = emptyList()
    ) {
        val conversation = conversationRepository.requireConversation(conversationId)

        val domain = credentialsManager.requireActiveDomain()
        val current = conversation.participants.toMutableSet()

        val removed = current.intersect(remove.toSet())
        current.removeAll(remove)

        val added = add.filterNot { current.contains(it) }
        current.addAll(added)

        val normalized = (current + domain).distinct()

        updateConversationInternal(
            conversationId = conversationId,
            title = conversation.name,
            recipients = normalized
        )

        trySendIntroductions(added, "$domain has added you to a group chat")

        var previousMessageId: Uuid? = null
        added.forEach { user ->
            val messageId = Uuid.random()
            chatMessageSenderService.sendSystemMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                messageText = "$domain added ${user.domainName}",
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }

        removed.forEach { user ->
            val messageId = Uuid.random()
            chatMessageSenderService.sendSystemMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                messageText = "$domain removed ${user.domainName}",
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }
    }

    suspend fun introduceEveryone(conversationId: Uuid, message: String?) {
        val conversation = conversationRepository.requireConversation(conversationId)
        trySendIntroductions(conversation.participants, message ?: "")
    }

    suspend fun updateConversation(
        conversationId: Uuid,
        title: String?,
        payloadBundle: PayloadBundle? = null
    ) {

        val conversation = conversationRepository.requireConversation(conversationId)
        val domain = credentialsManager.requireActiveDomain()

        var previousMessageId: Uuid? = null

        updateConversationInternal(
            conversationId = conversationId,
            title = title,
            recipients = conversation.participants,
            payloadBundle = payloadBundle
        )

        if (title != null && title != conversation.name) {

            val messageId = Uuid.random()

            chatMessageSenderService.sendSystemMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                messageText = "$domain changed the conversation title to \"$title\"",
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }

        if (payloadBundle != null) {

            val messageId = Uuid.random()

            chatMessageSenderService.sendSystemMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                messageText = "$domain updated the conversation photo",
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }
    }

    private suspend fun updateConversationInternal(
        conversationId: Uuid,
        title: String?,
        recipients: List<OdinId>,
        payloadBundle: PayloadBundle? = null
    ) {

        conversationUpdater.updateConversation(
            conversationId = conversationId,
            title = title,
            recipients = recipients,
            payloadBundle = payloadBundle
        )
    }


    private suspend fun resolveDisplayName(file: HomebaseFile): String {
        val author = file.fileMetadata.originalAuthor ?: return ""
        return contactService.resolveByOdinId(author)?.name ?: author.domainName
    }

    private fun normalizeRecipients(
        recipients: List<OdinId>,
        self: OdinId
    ): List<OdinId> =
        (recipients + self).distinct()

    private fun buildConversationContent(
        title: String?,
        recipients: List<OdinId>
    ) =
        ConversationAppDataJson(
            title = title ?: "",
            recipients = recipients,
            version = 1
        )

    private fun selectPreviewThumb(
        thumbs: List<EmbeddedThumb>
    ): EmbeddedThumb? =
        thumbs.minByOrNull { it.pixelWidth }

    private fun buildConversationMetadata(
        conversationId: Uuid,
        content: ConversationAppDataJson,
        previewThumb: EmbeddedThumb?,
        versionTag: Uuid? = null
    ): UploadFileMetadata =
        UploadFileMetadata(
            allowDistribution = true,
            isEncrypted = true,
            versionTag = versionTag,
            appData =
                UploadAppFileMetaData(
                    uniqueId = conversationId.toString(),
                    fileType = ChatProtocol.ConversationFileType,
                    content = OdinSystemSerializer.serialize(content),
                    previewThumbnail = previewThumb
                )
        )


    suspend fun trySendIntroductions(
        recipients: List<OdinId>,
        message: String
    ) {
        try {
            // send introductions
            introductionProvider.sendIntroductions(
                group = IntroductionGroup(
                    recipients = recipients,
                    message = message
                )
            )
        } catch (t: Throwable) {
            Logger.e("Failed sending introductions", t)
        }
    }
}