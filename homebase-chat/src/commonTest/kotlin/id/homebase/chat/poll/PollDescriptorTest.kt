package id.homebase.chat.poll

import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PollDescriptorTest {
    private fun valid(options: List<String> = listOf("A", "B")) =
        PollDescriptor(question = "Lunch?", options = options)

    @Test fun valid_minimal_poll_is_valid() = assertTrue(valid().isValid())

    @Test fun blank_question_is_invalid() =
        assertFalse(valid().copy(question = "   ").isValid())

    @Test fun question_over_140_codepoints_is_invalid() =
        assertFalse(valid().copy(question = "x".repeat(141)).isValid())

    @Test fun fewer_than_two_options_is_invalid() =
        assertFalse(valid(listOf("only")).isValid())

    @Test fun more_than_ten_options_is_invalid() =
        assertFalse(valid((1..11).map { "opt$it" }).isValid())

    @Test fun ten_options_is_valid() =
        assertTrue(valid((1..10).map { "opt$it" }).isValid())

    @Test fun blank_option_is_invalid() =
        assertFalse(valid(listOf("A", "  ")).isValid())

    @Test fun option_over_80_codepoints_is_invalid() =
        assertFalse(valid(listOf("A", "y".repeat(81))).isValid())

    @Test fun summaryLine_is_the_question() =
        assertTrue(valid().copy(question = "Pizza tonight?").summaryLine() == "Pizza tonight?")

    @Test fun closed_flag_does_not_affect_validity() =
        assertTrue(valid().copy(closed = true).isValid())

    // --- MessageContent.Poll + parser round-trip tests ---

    @Test fun round_trips_through_parser() {
        val poll = MessageContent.Poll(PollDescriptor("Q?", listOf("A", "B"), allowMultiple = true))
        val json = MessageContentParser.serialize(poll)
        val parsed = MessageContentParser.parse(ChatProtocol.ChatPollMessageDataType, json)
        assertEquals(poll, parsed)
    }

    @Test fun malformed_json_parses_to_null_descriptor_not_null() {
        val parsed = MessageContentParser.parse(ChatProtocol.ChatPollMessageDataType, "{not valid")
        assertEquals(MessageContent.Poll(descriptor = null), parsed)
        assertEquals(MessageContent.UNPARSEABLE_POLL_LABEL, (parsed as MessageContent.Poll).displayLabel)
    }

    @Test fun invalid_descriptor_parses_to_null_descriptor() {
        // Build a JSON whose PollDescriptor deserializes successfully but fails isValid() — exactly 1 option.
        val oneOptionJson = """{"question":"Q","options":["only"]}"""
        // Verify the JSON actually deserializes to a 1-option descriptor (testing !isValid() branch, not catch).
        val d = kotlinx.serialization.json.Json.decodeFromString(PollDescriptor.serializer(), oneOptionJson)
        assertFalse(d.isValid(), "1-option descriptor must be invalid so we're testing the !isValid() branch")
        val parsed = MessageContentParser.parse(ChatProtocol.ChatPollMessageDataType, oneOptionJson)
        assertEquals(MessageContent.Poll(descriptor = null), parsed)
    }
}
