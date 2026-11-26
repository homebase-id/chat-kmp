package id.homebase.chat.agentui

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatDeliveryStatus
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AgentUiDescriptorTest {
    private fun ui(fallback: String = "Draft post", title: String? = "Draft", rev: Int = 0) =
        AgentUiDescriptor(fallback = fallback, title = title, rev = rev)

    private fun action(
        actionId: String = "approve",
        label: String = "Approve",
        values: Map<String, String> = emptyMap(),
    ) = AgentUiActionDescriptor(target = Uuid.random(), rev = 1, actionId = actionId, label = label, values = values)

    @Test fun dataTypes_are_218_and_219() {
        assertEquals(219, ChatProtocol.ChatAgentUiMessageDataType)
        assertEquals(220, ChatProtocol.ChatAgentUiActionMessageDataType)
        assertEquals(8, ChatProtocol.PAYLOAD_KEY_AGENT_UI.length)
    }

    @Test fun descriptor_boundaries() {
        assertTrue(ui().isValid())
        assertTrue(ui(fallback = "x".repeat(1000)).isValid())
        assertFalse(ui(fallback = "x".repeat(1001)).isValid())
        assertFalse(ui(fallback = "  ").isValid())
        assertTrue(ui(title = null).isValid())
        assertTrue(ui(title = "t".repeat(80)).isValid())
        assertFalse(ui(title = "t".repeat(81)).isValid())
        assertTrue(ui(rev = 0).isValid())
        assertFalse(ui(rev = -1).isValid())
        assertFalse(AgentUiDescriptor(schemaVersion = 2, fallback = "x").isValid())
    }

    @Test fun descriptor_summary_prefers_title_then_truncated_fallback() {
        assertEquals("Draft", ui().summaryLine())
        assertEquals("y".repeat(80), ui(fallback = "y".repeat(500), title = null).summaryLine())
    }

    @Test fun action_descriptor_boundaries() {
        assertTrue(action().isValid())
        assertTrue(action(actionId = "a".repeat(64)).isValid())
        assertFalse(action(actionId = "a".repeat(65)).isValid())
        assertFalse(action(actionId = "").isValid())
        assertFalse(action(actionId = "Approve").isValid())
        assertFalse(action(actionId = "a b").isValid())
        assertFalse(action(label = " ").isValid())
        assertTrue(action(values = (1..20).associate { "k$it" to "v" }).isValid())
        assertFalse(action(values = (1..21).associate { "k$it" to "v" }).isValid())
        assertFalse(action(values = mapOf("k" to "v".repeat(1001))).isValid())
        assertFalse(action(values = (1..5).associate { "k$it" to "v".repeat(1000) }).isValid())
        assertFalse(action().copy(rev = -1).isValid())
    }

    @Test fun agent_ui_round_trips() {
        val d = ui(rev = 3)
        val content = MessageContent.AgentUi(d)
        val json = MessageContentParser.serialize(content)
        assertEquals(ChatProtocol.ChatAgentUiMessageDataType, MessageContentParser.dataTypeFor(content))
        assertEquals(content, MessageContentParser.parse(219, json))
        assertTrue(MessageContentParser.usesRawHeaderContent(content))
    }

    @Test fun action_round_trips() {
        val d = action(values = mapOf("caption" to "Hi"))
        val content = MessageContent.AgentUiAction(d)
        val json = MessageContentParser.serialize(content)
        assertEquals(ChatProtocol.ChatAgentUiActionMessageDataType, MessageContentParser.dataTypeFor(content))
        assertEquals(content, MessageContentParser.parse(220, json))
        assertTrue(MessageContentParser.usesRawHeaderContent(content))
    }

    @Test fun malformed_headers_keep_the_kind_with_null_descriptor() {
        val ui = MessageContentParser.parse(219, "not json")
        assertIs<MessageContent.AgentUi>(ui)
        assertNull(ui.descriptor)
        assertEquals(MessageContent.UNPARSEABLE_AGENT_UI_LABEL, ui.displayLabel)

        val invalid = MessageContentParser.parse(219, """{"schemaVersion":1,"fallback":"","rev":0}""")
        assertIs<MessageContent.AgentUi>(invalid)
        assertNull(invalid.descriptor)

        val act = MessageContentParser.parse(220, """{"target":"nope"}""")
        assertIs<MessageContent.AgentUiAction>(act)
        assertNull(act.descriptor)
        assertEquals(MessageContent.UNPARSEABLE_AGENT_ACTION_LABEL, act.displayLabel)
    }

    @Test fun action_policies() {
        val p = MessageContent.AgentUi(ui()).actions
        assertFalse(p.allowEdit)
        assertTrue(p.allowReply)
        assertFalse(p.allowForward)
        assertFalse(p.allowShare)
        assertTrue(p.allowInlineReactions)
        assertTrue(p.allowReactionDetails)
        assertTrue(p.allowCopy)
        val a = MessageContent.AgentUiAction(action())
        assertFalse(a.actions.allowCopy)
        assertFalse(a.actions.allowReply)
        assertFalse(a.pushesToRecipient)
        assertTrue(MessageContent.AgentUi(ui()).pushesToRecipient)
        assertNotNull(a.displayLabel)
        assertEquals("Approve", a.displayLabel)
    }

    @Test fun an_unreserved_future_kind_is_still_unknown() {
        assertIs<MessageContent.Unknown>(MessageContentParser.parse(999, """{"x":1}"""))
    }

    @Test fun reply_values_are_shown_only_when_short() {
        assertNull(action().shortValues())
        assertEquals("nets: x,li", action(values = mapOf("nets" to "x,li")).shortValues())
        assertNull(action(values = mapOf("caption" to "y".repeat(80))).shortValues())
    }

    @Test fun answered_by_you_counts_only_your_replies_at_the_current_rev() {
        val card = Uuid.random()
        val c = AgentUiConversation(
            mapOf(card to listOf(AgentUiAnswer(1, "keep", byYou = true), AgentUiAnswer(0, "drop", byYou = true), AgentUiAnswer(1, "drop", byYou = false))),
        )
        assertEquals(setOf("keep"), c.answeredByYou(card, 1))
        assertEquals(setOf("drop"), c.answeredByYou(card, 0))
        assertEquals(emptySet(), c.answeredByYou(Uuid.random(), 1))
    }

    private fun reply(target: Uuid, actionId: String, status: ChatDeliveryStatus = ChatDeliveryStatus.Sent) = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.random(),
        content = "",
        userDate = Instant.fromEpochMilliseconds(0),
        modified = null,
        created = Instant.fromEpochMilliseconds(0),
        originalAuthor = OdinId("me.example.com"),
        sender = OdinId("me.example.com"),
        displayName = "Me",
        messageAppData = MessageAppData(deliveryStatus = status.value),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = null,
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
        messageContent = MessageContent.AgentUiAction(
            AgentUiActionDescriptor(target = target, rev = 1, actionId = actionId, label = actionId),
        ),
    )

    // A failed reply un-checks its button, so the action can be sent again.
    @Test fun a_failed_reply_does_not_count_as_answered() {
        val card = Uuid.random()
        val c = agentUiConversation(listOf(reply(card, "keep"), reply(card, "drop", ChatDeliveryStatus.Failed))) { true }
        assertEquals(setOf("keep"), c.answeredByYou(card, 1))
    }

    @Test fun conversations_with_the_same_cards_and_replies_are_equal() {
        val card = Uuid.random()
        val replies = listOf(reply(card, "keep"))
        assertEquals(agentUiConversation(replies) { true }, agentUiConversation(replies + reply(Uuid.random(), "x", ChatDeliveryStatus.Failed)) { true })
        assertNotEquals(agentUiConversation(replies) { true }, agentUiConversation(replies) { false })
    }

    // Copy on a card copies its text and table rows, not the 80-character summary.
    @Test fun a_card_copies_as_its_text_and_table_rows() {
        assertEquals("Draft post\nSpring is here. **Meet** the new release.", AgentUiFixtures.decodeDraftPostCard().plainText())
        val table = AgentUiFixtures.valid(
            AgentUiFixtures.tree(
                """{"id":"root","type":"column","children":["t","tb"]}""",
                """{"id":"t","type":"text","markdown":"Totals"}""",
                """{"id":"tb","type":"table","header":["Net","Posts"],"rows":[["X","3"],["LinkedIn","1"]]}""",
            ),
        )
        assertEquals("Totals\nNet\tPosts\nX\t3\nLinkedIn\t1", table.plainText())
    }

    @Test fun a_card_copies_its_list_items_and_facts() {
        val t = AgentUiFixtures.valid(
            AgentUiFixtures.tree(
                """{"id":"root","type":"column","children":["li","f"]}""",
                """{"id":"li","type":"listItem","title":"Order #1042","subtitle":"2 items","trailing":"b"}""",
                """{"id":"b","type":"badge","label":"Shipped"}""",
                """{"id":"f","type":"facts","items":[{"label":"Total","value":"$42.00"}]}""",
            ),
        )
        assertEquals("Order #1042\n2 items\nTotal\t$42.00", t.plainText())
    }
}
