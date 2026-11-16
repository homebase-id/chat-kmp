package id.homebase.chat.agentui

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.content.MessageContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AgentUiConversationTest {
    private val me = OdinId("me.example.com")

    private fun message(content: MessageContent?, author: String = "agent.example.com", name: String = "Agent", atMs: Long = 0) = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.NIL,
        content = "",
        userDate = Instant.fromEpochMilliseconds(atMs),
        modified = null,
        created = Instant.fromEpochMilliseconds(atMs),
        originalAuthor = OdinId(author),
        sender = OdinId(author),
        displayName = name,
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = null,
        keyHeader = KeyHeader.empty(),
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
        messageContent = content,
    )

    private fun card(rev: Int = 0) = message(MessageContent.AgentUi(AgentUiDescriptor(fallback = "Post plan", title = "Post plan", rev = rev)))
    private fun text() = message(null)

    private fun reply(target: Uuid, label: String, author: String = me.domainName, name: String = "Me", atMs: Long = 0, rev: Int = 0) =
        message(
            MessageContent.AgentUiAction(AgentUiActionDescriptor(target = target, rev = rev, actionId = label.lowercase(), label = label)),
            author, name, atMs,
        )

    private fun conversation(messages: List<MessageUiModel>) = agentUiConversation(messages) { it.originalAuthor == me }

    @Test fun a_reply_to_a_loaded_card_folds_onto_it_and_leaves_the_rows() {
        val card = card()
        val before = text()
        val folded = reply(card.id, "Keep")
        val orphan = reply(Uuid.random(), "Drop")
        val after = text()
        val (shown, onCards) = foldAgentUiReplies(listOf(before, card, folded, orphan, after))
        assertEquals(listOf(before, card, orphan, after), shown)
        assertEquals(listOf(folded), onCards)
    }

    @Test fun without_replies_nothing_folds() {
        val messages = listOf(card(), text())
        assertEquals(messages to emptyList(), foldAgentUiReplies(messages))
    }

    @Test fun the_card_shows_each_senders_latest_choice_at_its_rev_yours_first() {
        val card = card(rev = 1)
        val c = conversation(
            listOf(
                card,
                reply(card.id, "Rewrite", "alice.example.com", "Alice", atMs = 10, rev = 1),
                reply(card.id, "Drop", atMs = 5, rev = 0),
                reply(card.id, "Keep", atMs = 20, rev = 1),
                reply(card.id, "Keep", "alice.example.com", "Alice", atMs = 30, rev = 1),
                reply(card.id, "Drop", "bob.example.com", "Bob", atMs = 25, rev = 1),
            ),
        )
        val choices = c.choices(card.id, 1)
        assertEquals(listOf("Me" to "Keep", "Bob" to "Drop", "Alice" to "Keep"), choices.shown.map { it.name to it.label })
        assertEquals(listOf(true, false, false), choices.shown.map { it.byYou })
        assertEquals(Instant.fromEpochMilliseconds(20), choices.shown.first().at)
        assertEquals(0, choices.more)
        assertEquals(listOf("Drop"), c.choices(card.id, 0).shown.map { it.label })
    }

    @Test fun past_three_choices_the_card_counts_the_rest() {
        val card = card()
        val people = (1..5).map { reply(card.id, "Keep", "p$it.example.com", "P$it", atMs = it.toLong()) }
        val choices = conversation(listOf(card) + people).choices(card.id, 0)
        assertEquals(listOf("P1", "P2", "P3"), choices.shown.map { it.name })
        assertEquals(2, choices.more)
    }

    // Equality covers what the card renders, so a renamed sender or a new label still recomposes it.
    @Test fun a_change_the_card_shows_makes_the_conversation_unequal() {
        val card = card()
        val r = reply(card.id, "Keep", "alice.example.com", "Alice", atMs = 10)
        assertEquals(conversation(listOf(card, r)), conversation(listOf(card, r)))
        assertNotEquals(conversation(listOf(card, r)), conversation(listOf(card, r.copy(displayName = "Alicia"))))
        assertNotEquals(conversation(listOf(card, r)), conversation(listOf(card, r.copy(userDate = Instant.fromEpochMilliseconds(11)))))
    }
}
