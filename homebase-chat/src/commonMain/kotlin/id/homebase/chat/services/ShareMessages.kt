package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.common.OdinId
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.convo.ConversationService
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/** Tells each person a share reaches about it, in their one-to-one chat: opening the message is how they find what's shared. */
class ShareMessages(
    private val conversations: ConversationService,
    private val sender: ChatMessageSenderService,
) {
    /** The ones it couldn't tell. */
    suspend fun send(members: List<OdinId>, share: MessageContent): List<OdinId> =
        members.filter { member ->
            try {
                val conversationId = conversations.oneOnOneConversationId(member)
                sender.sendNewTypedMessage(Uuid.random(), conversationId, share, previousMessageUniqueId = null)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "no share message for $member" }
                true
            }
        }

    private companion object {
        const val TAG = "ShareMessages"
    }
}
