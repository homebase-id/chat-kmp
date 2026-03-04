package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.ConnectionIntroductionProvider
import id.homebase.api.client.connections.IntroductionGroup
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.uuid.Uuid

class ConversationDistributor(
    private val credentialsManager: CredentialsManager,
    private val conversationRepository: ConversationRepository,
    private val conversationUpdater: ConversationUpdater,
    private val introductionProvider: ConnectionIntroductionProvider
) {

    suspend fun ensureRecipientsHaveConversation(conversationId: Uuid) {

        val self = credentialsManager.requireActiveDomain()

        val conversation =
            conversationRepository.getConversationHomebaseFile(conversationId) ?: return;

        val recipients =
            OdinSystemSerializer.deserialize<ConversationAppDataJson>(
                conversation.fileMetadata.appData.content ?: return
            ).recipients

        val filteredRecipients =
            recipients.filter { it != self }

        val serverMetadata = conversation.serverMetadata

        val anyRecipientMissingConversation =
            serverMetadata.originalRecipientCount !=
                    serverMetadata.transferHistory?.summary?.totalDelivered

        if (anyRecipientMissingConversation) {

            redistributeConversation(conversationId)

            if (filteredRecipients.size > 1) {
                trySendIntroductions(filteredRecipients, "$self has added you to a group chat")
            }

        }
    }

    suspend fun redistributeConversation(conversationId: Uuid) {

        val conversation = conversationRepository.requireConversation(conversationId)

        conversationUpdater.updateConversation(
            conversationId = conversationId,
            title = conversation.name,
            recipients = conversation.participants
        )
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