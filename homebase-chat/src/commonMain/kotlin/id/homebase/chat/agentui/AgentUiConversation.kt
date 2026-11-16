package id.homebase.chat.agentui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatDeliveryStatus
import id.homebase.chat.services.content.MessageContent
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** One action reply (dataType 220) in the loaded conversation, read back onto the card it answered. */
data class AgentUiAnswer(val rev: Int, val actionId: String, val byYou: Boolean)

/**
 * The cards and action replies of the loaded conversation slice, so a reply can name the card it answered
 * and a card can mark what was already chosen at its current rev, even after the app restarts.
 */
@Immutable
class AgentUiConversation(
    /** What each loaded card is about, by card message id. */
    val subjects: Map<Uuid, String>,
    /** Action replies by the card message id they target. */
    val answers: Map<Uuid, List<AgentUiAnswer>>,
    /** A payload card's tree when it is already loaded; inline trees are read from the message. */
    private val loadedTree: (MessageUiModel) -> AgentUiTreeResult? = { null },
) {
    /** Action ids you already sent for [card] at [rev]; the card shows them as chosen and never sends them again. */
    fun answeredByYou(card: Uuid, rev: Int): Set<String> =
        answers[card].orEmpty().filter { it.byYou && it.rev == rev }.mapTo(HashSet()) { it.actionId }

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

    // Equal by its maps, so a message update that touches no card or reply recomposes no bubble.
    override fun equals(other: Any?): Boolean =
        other is AgentUiConversation && subjects == other.subjects && answers == other.answers

    override fun hashCode(): Int = 31 * subjects.hashCode() + answers.hashCode()

    companion object {
        val Empty = AgentUiConversation(emptyMap(), emptyMap())
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
        answers.getOrPut(d.target) { ArrayList() } += AgentUiAnswer(d.rev, d.actionId, byYou(m))
    }
    if (answers.isEmpty()) return AgentUiConversation(emptyMap(), emptyMap(), loadedTree)
    val subjects = HashMap<Uuid, String>()
    for (m in messages) {
        if (m.id !in answers) continue
        (m.messageContent as? MessageContent.AgentUi)?.subject()?.let { subjects[m.id] = it }
    }
    return AgentUiConversation(subjects, answers, loadedTree)
}

/** The tree as plain text, one line per text node and table row (cells tab-separated), in reading order. */
internal fun AgentUiTree.plainText(): String = buildList { root.collectText(this) }.joinToString("\n")

private fun AgentUiNode.collectText(out: MutableList<String>) {
    when (this) {
        is AgentUiNode.Text -> markdown.takeIf { it.isNotBlank() }?.let(out::add)
        is AgentUiNode.Table -> (listOf(header) + rows).filter { it.isNotEmpty() }.forEach { out += it.joinToString("\t") }
        else -> children()?.forEach { it.collectText(out) }
    }
}

private const val SUBJECT_CP = 60
private val MarkdownMarks = Regex("""[*_`#>~\[\]]""")

/** The header title, else the card's first line of text, else its fallback, cut to one short line. */
internal fun MessageContent.AgentUi.subject(): String? {
    val d = descriptor ?: return null
    val text = d.title?.takeIf { it.isNotBlank() }
        ?: (inlineTree as? AgentUiTreeResult.Valid)?.tree?.root?.firstText()
        ?: d.fallback
    val line = text.lineSequence().map { it.replace(MarkdownMarks, "").trim() }.firstOrNull { it.isNotEmpty() } ?: return null
    val cut = line.truncateToCodePoints(SUBJECT_CP)
    return if (cut.length < line.length) cut.trimEnd() + "…" else cut
}

private fun AgentUiNode.firstText(): String? {
    if (this is AgentUiNode.Text && markdown.isNotBlank()) return markdown
    return children()?.firstNotNullOfOrNull { it.firstText() }
}

/** "caption: Hello" when the reply's values are short enough to read at a glance; null otherwise. */
internal fun AgentUiActionDescriptor.shortValues(): String? {
    if (values.isEmpty()) return null
    val s = values.entries.joinToString(", ") { (k, v) -> "$k: $v" }
    return s.takeIf { it.length <= SUBJECT_CP && '\n' !in it }
}
