package id.homebase.chat.services.content

import id.homebase.chat.services.ChatProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Kind-agnostic parser routing tests. Per-kind parsing (Event, DiceRoll) lives
 * in the kind's own test file.
 */
class MessageContentParserTest {

    @Test
    fun parse_returns_unknown_for_unrecognized_non_zero_datatype() {
        // A future Poll = 215 (or any non-allowlisted dataType) arriving at an
        // older client must surface as Unknown so the user sees an "update the
        // app" chip rather than a vanished message.
        val parsed = MessageContentParser.parse(dataType = 215, content = "{\"foo\":1}")
        val unknown = assertIs<MessageContent.Unknown>(parsed)
        assertEquals(215, unknown.dataType)
        assertEquals(MessageContent.UNKNOWN_LABEL, unknown.displayLabel)
    }

    @Test
    fun parse_returns_null_for_plain_text_dataType() {
        // dataType=0 is plain text/media — the caller deserializes content as
        // MessageAppData. Parser must bow out with null, NOT route to Unknown.
        val parsed = MessageContentParser.parse(dataType = 0, content = "{\"message\":\"hi\"}")
        assertNull(parsed)
    }

    @Test
    fun parse_returns_null_for_location_dataType() {
        // Location's descriptor lives on a payload; the header content is a
        // MessageAppData, same shape as plain text. Parser stays out of the way.
        val parsed = MessageContentParser.parse(
            dataType = ChatProtocol.ChatLocationMessageDataType,
            content = "{\"message\":\"\"}",
        )
        assertNull(parsed)
    }

    @Test
    fun parse_returns_null_for_status_dataType() {
        // ChatMessageStream filters status messages before reaching the parser,
        // but the Defragmenter probe also calls parse() — listing status here
        // prevents a false-positive Unknown chip from being conjured for
        // historical status files.
        val parsed = MessageContentParser.parse(
            dataType = ChatProtocol.ChatStatusMessageDataType,
            content = "{}",
        )
        assertNull(parsed)
    }

    @Test
    fun parse_returns_null_for_null_or_blank_content() {
        assertNull(MessageContentParser.parse(dataType = 215, content = null))
        assertNull(MessageContentParser.parse(dataType = 215, content = ""))
        assertNull(MessageContentParser.parse(dataType = 215, content = "   "))
    }

    @Test
    fun parse_returns_null_when_dataType_is_null() {
        assertNull(MessageContentParser.parse(dataType = null, content = "{}"))
    }

    @Test
    fun serialize_throws_on_unknown() {
        // Receivers must never re-send something they don't understand.
        assertFailsWith<IllegalStateException> {
            MessageContentParser.serialize(MessageContent.Unknown(dataType = 215))
        }
    }

    @Test
    fun dataTypeFor_throws_on_unknown() {
        assertFailsWith<IllegalStateException> {
            MessageContentParser.dataTypeFor(MessageContent.Unknown(dataType = 215))
        }
    }
}
