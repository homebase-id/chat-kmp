package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import kotlin.uuid.Uuid

class Allowlist(
    val conversationIds: Set<Uuid>,
    val authors: Set<OdinId>,
    val memberMode: Boolean = false,
    val authorsAnyMember: Boolean = false,
    val groupSend: Boolean = false,
    val delegate: Boolean = false,
) {
    init {
        require(!(delegate && memberMode)) { "member mode is not permitted for the me profile; list conversation uuids explicitly" }
    }

    private var known: Map<Uuid, ConversationInfo> = emptyMap()

    fun learn(conversations: List<ConversationInfo>) {
        known = conversations.associateBy { it.id }
    }

    fun allowedConversationIds(): Set<Uuid> = if (memberMode) conversationIds + known.keys else conversationIds

    fun title(id: Uuid): String? =
        if (id == ChatProtocol.ConversationWithYourselfId) NOTE_TO_SELF_TITLE else known[id]?.title

    fun memberCount(id: Uuid): Int? =
        if (id == ChatProtocol.ConversationWithYourselfId) 1 else known[id]?.members?.size

    fun allowsConversation(id: Uuid): Boolean = id in conversationIds || (memberMode && id in known)

    fun allowsAuthor(author: OdinId?): Boolean = author != null && author in authors

    fun allowsAuthor(author: OdinId?, conversationId: Uuid): Boolean =
        allowsAuthor(author) ||
            ((authorsAnyMember || (delegate && isExplicitGroup(conversationId))) &&
                author != null && known[conversationId]?.members?.contains(author) == true)

    fun isExplicitGroup(id: Uuid) = id != ChatProtocol.ConversationWithYourselfId && id in conversationIds

    val hasExplicitGroups get() = conversationIds.any { it != ChatProtocol.ConversationWithYourselfId }

    fun info(id: Uuid): ConversationInfo? = known[id]

    fun allowsSend(id: Uuid): Boolean = when {
        id == ChatProtocol.ConversationWithYourselfId -> allowsConversation(id)
        delegate -> isExplicitGroup(id) && id in known
        else -> allowsConversation(id) && groupSend && id in known
    }

    fun disclosure(id: Uuid, text: String, owner: OdinId): String =
        if (delegate && id != ChatProtocol.ConversationWithYourselfId) {
            "$BOT_PREFIX ${owner.domainName}'s AI assistant: ${text.removePrefix(BOT_PREFIX).trimStart()}"
        } else {
            text
        }

    fun requireSend(id: Uuid) =
        require(allowsSend(id)) { "sending to conversation $id is not permitted for this profile" }

    fun requireConversation(id: Uuid) =
        require(allowsConversation(id)) { "conversation $id is not on the allowlist" }

    companion object {
        fun default(owner: OdinId) = Allowlist(setOf(ChatProtocol.ConversationWithYourselfId), setOf(owner))
    }
}
