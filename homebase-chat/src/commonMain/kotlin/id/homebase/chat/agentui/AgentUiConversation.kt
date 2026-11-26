package id.homebase.chat.agentui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatDeliveryStatus
import id.homebase.chat.services.content.MessageContent
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** One action reply (dataType 220) in the loaded conversation, read back onto the card it answered. */
data class AgentUiAnswer(
    val rev: Int,
    val actionId: String,
    val byYou: Boolean,
    val label: String = actionId,
    /** The replying identity's domain; null reads as you. */
    val sender: String? = null,
    val name: String = "",
    val at: Instant = Instant.DISTANT_PAST,
)

/** What a card shows of its answers: up to [MAX_SHOWN] choices, yours first, and how many more there are. */
data class AgentUiChoices(val shown: List<AgentUiAnswer>, val more: Int) {
    companion object {
        const val MAX_SHOWN = 3
    }
}

/**
 * The action replies of the loaded conversation slice, so a card can mark and show what was already
 * chosen at its current rev, even after the app restarts.
 */
@Immutable
class AgentUiConversation(
    /** Action replies by the card message id they target. */
    val answers: Map<Uuid, List<AgentUiAnswer>>,
    /** A payload card's tree when it is already loaded; inline trees are read from the message. */
    private val loadedTree: (MessageUiModel) -> AgentUiTreeResult? = { null },
) {
    /** Action ids you already sent for [card] at [rev]; the card shows them as chosen and never sends them again. */
    fun answeredByYou(card: Uuid, rev: Int): Set<String> =
        answers[card].orEmpty().filter { it.byYou && it.rev == rev }.mapTo(HashSet()) { it.actionId }

    /** Each sender's latest answer to [card] at [rev], yours first, then oldest first, like votes on a poll. */
    fun choices(card: Uuid, rev: Int): AgentUiChoices {
        val latest = answers[card].orEmpty().filter { it.rev == rev }
            .groupBy { if (it.byYou) null else it.sender }
            .map { (_, list) -> list.maxBy { it.at } }
            .sortedWith(compareBy<AgentUiAnswer> { !it.byYou }.thenBy { it.at })
        return AgentUiChoices(latest.take(AgentUiChoices.MAX_SHOWN), (latest.size - AgentUiChoices.MAX_SHOWN).coerceAtLeast(0))
    }

    /** What Copy puts on the clipboard for a card: its title, text and table rows; the fallback while the tree is not loaded. */
    fun copyText(message: MessageUiModel): String? {
        val content = message.messageContent as? MessageContent.AgentUi ?: return null
        val d = content.descriptor ?: return null
        val tree = (content.inlineTree as? AgentUiTreeResult.Valid ?: loadedTree(message) as? AgentUiTreeResult.Valid)?.tree
            ?: return d.fallback
        val body = tree.plainText()
        val title = d.title?.takeIf { it.isNotBlank() && !body.startsWith(it) }
        return listOfNotNull(title, body.takeIf { it.isNotBlank() }).joinToString("\n").ifBlank { d.fallback }
    }

    // Equal by its answers (each with the label, name and time the card shows), so a message update that
    // touches no reply recomposes no bubble, and any change a card renders still does.
    override fun equals(other: Any?): Boolean = other is AgentUiConversation && answers == other.answers

    override fun hashCode(): Int = answers.hashCode()

    companion object {
        val Empty = AgentUiConversation(emptyMap())
    }
}

val LocalAgentUiConversation = compositionLocalOf { AgentUiConversation.Empty }

@OptIn(ExperimentalUuidApi::class)
fun agentUiConversation(
    messages: List<MessageUiModel>,
    loadedTree: (MessageUiModel) -> AgentUiTreeResult? = { null },
    byYou: (MessageUiModel) -> Boolean,
): AgentUiConversation {
    val answers = HashMap<Uuid, MutableList<AgentUiAnswer>>()
    for (m in messages) {
        val d = (m.messageContent as? MessageContent.AgentUiAction)?.descriptor ?: continue
        // A reply that failed to send answers nothing, so its button can be pressed again.
        if (m.messageAppData.deliveryStatus == ChatDeliveryStatus.Failed.value) continue
        answers.getOrPut(d.target) { ArrayList() } +=
            AgentUiAnswer(d.rev, d.actionId, byYou(m), d.label, m.originalAuthor?.domainName, m.displayName, m.userDate)
    }
    return AgentUiConversation(answers, loadedTree)
}

/**
 * Splits [messages] into the rows the list shows and the action replies it folds onto their card:
 * a reply whose card is loaded shows on that card, like a poll vote, so it never breaks the agent's
 * message cluster. A reply whose card is not loaded stays a row.
 */
@OptIn(ExperimentalUuidApi::class)
fun foldAgentUiReplies(messages: List<MessageUiModel>): Pair<List<MessageUiModel>, List<MessageUiModel>> {
    if (messages.none { it.messageContent is MessageContent.AgentUiAction }) return messages to emptyList()
    val cards = messages.filter { it.messageContent is MessageContent.AgentUi }.mapTo(HashSet()) { it.id }
    return messages.partition { m ->
        val target = (m.messageContent as? MessageContent.AgentUiAction)?.descriptor?.target
        target == null || target !in cards
    }
}

/** The tree as plain text, one line per text node, list item line, fact and table row (cells tab-separated), in reading order. */
internal fun AgentUiTree.plainText(): String = buildList { root.collectText(this) }.joinToString("\n")

private fun AgentUiNode.collectText(out: MutableList<String>) {
    when (this) {
        is AgentUiNode.Text -> markdown.takeIf { it.isNotBlank() }?.let(out::add)
        is AgentUiNode.Table -> (listOf(header) + rows).filter { it.isNotEmpty() }.forEach { out += it.joinToString("\t") }
        is AgentUiNode.Facts -> items.forEach { f -> out += listOf(f.label, f.value).filter { it.isNotBlank() }.joinToString("\t") }
        is AgentUiNode.ListItem -> {
            listOfNotNull(title.takeIf { it.isNotBlank() }, subtitle, caption).forEach(out::add)
            children()?.forEach { it.collectText(out) }
        }
        else -> children()?.forEach { it.collectText(out) }
    }
}

private const val SHORT_VALUES_CP = 60

/** "caption: Hello" when the reply's values are short enough to read at a glance; null otherwise. */
internal fun AgentUiActionDescriptor.shortValues(): String? {
    if (values.isEmpty()) return null
    val s = values.entries.joinToString(", ") { (k, v) -> "$k: $v" }
    return s.takeIf { it.length <= SHORT_VALUES_CP && '\n' !in it }
}
