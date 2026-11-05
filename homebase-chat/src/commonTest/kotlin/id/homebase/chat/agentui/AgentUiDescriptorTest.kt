package id.homebase.chat.agentui

import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
        assertEquals(218, ChatProtocol.ChatAgentUiMessageDataType)
        assertEquals(219, ChatProtocol.ChatAgentUiActionMessageDataType)
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
        assertEquals(content, MessageContentParser.parse(218, json))
        assertTrue(MessageContentParser.usesRawHeaderContent(content))
    }

    @Test fun action_round_trips() {
        val d = action(values = mapOf("caption" to "Hi"))
        val content = MessageContent.AgentUiAction(d)
        val json = MessageContentParser.serialize(content)
        assertEquals(ChatProtocol.ChatAgentUiActionMessageDataType, MessageContentParser.dataTypeFor(content))
        assertEquals(content, MessageContentParser.parse(219, json))
        assertTrue(MessageContentParser.usesRawHeaderContent(content))
    }

    @Test fun malformed_headers_keep_the_kind_with_null_descriptor() {
        val ui = MessageContentParser.parse(218, "not json")
        assertIs<MessageContent.AgentUi>(ui)
        assertNull(ui.descriptor)
        assertEquals(MessageContent.UNPARSEABLE_AGENT_UI_LABEL, ui.displayLabel)

        val invalid = MessageContentParser.parse(218, """{"schemaVersion":1,"fallback":"","rev":0}""")
        assertIs<MessageContent.AgentUi>(invalid)
        assertNull(invalid.descriptor)

        val act = MessageContentParser.parse(219, """{"target":"nope"}""")
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
}
