package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import kotlin.uuid.Uuid

enum class Kind { DELEGATE, BOT, PLAIN }

class Allowlist(
    val conversationIds: Set<Uuid>,
    val authors: Set<OdinId>?,
    val kind: Kind = Kind.PLAIN,
    val memberMode: Boolean = false,
    private val selfOwned: Boolean = false,
    val scope: Uuid? = null,
    val readOnly: Boolean = false,
) {
    init {
        require(!(kind == Kind.DELEGATE && memberMode)) { "member mode is not permitted for the me profile; list conversation uuids explicitly" }
    }

    val delegate get() = kind == Kind.DELEGATE
    val authorsAnyMember get() = authors == null
    val groupSend get() = kind == Kind.BOT && !selfOwned

    private var known: Map<Uuid, ConversationInfo> = emptyMap()
    private val derived = HashMap<Uuid, ConversationInfo>()

    fun copy(scope: Uuid?, readOnly: Boolean) =
        Allowlist(conversationIds, authors, kind, memberMode, selfOwned, scope, readOnly).also {
            it.known = known
            it.derived.putAll(derived)
        }

    fun learn(conversations: List<ConversationInfo>) {
        known = conversations.associateBy { it.id }
    }

    fun learnDerived(conversation: ConversationInfo) {
        derived[conversation.id] = conversation
    }

    private fun lookup(id: Uuid): ConversationInfo? = known[id] ?: derived[id]

    fun allowedConversationIds(): Set<Uuid> =
        (if (memberMode) conversationIds + known.keys + derived.keys else conversationIds).let { all ->
            scope?.let { s -> all.filterTo(HashSet()) { it == s } } ?: all
        }

    fun title(id: Uuid): String? =
        if (id == ChatProtocol.ConversationWithYourselfId) NOTE_TO_SELF_TITLE else lookup(id)?.title

    fun memberCount(id: Uuid): Int? =
        if (id == ChatProtocol.ConversationWithYourselfId) 1 else lookup(id)?.members?.size

    fun isDirect(id: Uuid): Boolean = id != ChatProtocol.ConversationWithYourselfId && memberCount(id) == 2

    fun allowsConversation(id: Uuid): Boolean =
        (scope == null || id == scope) && (id in conversationIds || (memberMode && lookup(id) != null))

    fun allowsAuthor(author: OdinId?): Boolean = author != null && authors != null && author in authors

    fun allowsAuthor(author: OdinId?, conversationId: Uuid): Boolean =
        allowsAuthor(author) ||
            ((authorsAnyMember || (delegate && isExplicitGroup(conversationId))) &&
                author != null && lookup(conversationId)?.members?.contains(author) == true)

    fun isExplicitGroup(id: Uuid) = id != ChatProtocol.ConversationWithYourselfId && id in conversationIds

    val hasExplicitGroups get() = conversationIds.any { it != ChatProtocol.ConversationWithYourselfId }

    fun info(id: Uuid): ConversationInfo? = lookup(id)

    fun allowsSend(id: Uuid): Boolean = !readOnly && when {
        id == ChatProtocol.ConversationWithYourselfId -> allowsConversation(id)
        delegate -> isExplicitGroup(id) && lookup(id) != null
        else -> allowsConversation(id) && groupSend && lookup(id) != null
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
