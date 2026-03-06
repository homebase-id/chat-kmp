package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.ConnectionIntroductionProvider
import id.homebase.api.client.connections.IntroductionGroup
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatMessageSenderService
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

        conversationRepository.createConversationFile(
            newConversationId,
            title,
            recipients,
            payloadBundle,
            scope
        )

        if (isGroup) {
            trySendIntroductions(recipients, "$domain has added you to a group chat")

            chatMessageSenderService.sendSystemMessage(
                messageUniqueId = Uuid.random(),
                conversationId = newConversationId,
                messageText = "$domain started this group titled $title"
            )
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
            unencryptedPayloadBundle = payloadBundle
        )
    }


    private suspend fun resolveDisplayName(file: HomebaseFile): String {
        val author = file.fileMetadata.originalAuthor ?: return ""
        return contactService.resolveByOdinId(author)?.name ?: author.domainName
    }


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