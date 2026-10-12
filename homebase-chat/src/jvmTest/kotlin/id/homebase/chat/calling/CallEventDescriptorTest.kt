package id.homebase.chat.calling

import id.homebase.api.serialization.OdinSystemSerializer
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

class CallEventDescriptorTest {
    @Test
    fun roundTripsThroughTheParser() {
        val content = MessageContent.CallEvent(CallEventDescriptor(CallOutcome.Completed, video = true, durationSeconds = 93))
        val json = MessageContentParser.serialize(content)
        assertEquals(ChatProtocol.ChatCallEventMessageDataType, MessageContentParser.dataTypeFor(content))
        assertEquals(content, MessageContentParser.parse(ChatProtocol.ChatCallEventMessageDataType, json))
    }

    @Test
    fun descriptorCarriesNoEnvelopeFields() {
        val json = OdinSystemSerializer.serialize(CallEventDescriptor(CallOutcome.NoAnswer))
        listOf("callId", "author", "odinId", "createdAt", "timestamp").forEach { assertFalse(json.contains(it, ignoreCase = true), it) }
    }

    @Test
    fun malformedOrInvalidDescriptorsParseToAnUnparseableChipNotNull() {
        listOf("not json", """{"outcome":"Declined","durationSeconds":5}""", """{"outcome":"Completed","durationSeconds":-1}""").forEach {
            val parsed = MessageContentParser.parse(ChatProtocol.ChatCallEventMessageDataType, it)
            assertIs<MessageContent.CallEvent>(parsed)
            assertNull(parsed.descriptor)
            assertEquals(MessageContent.UNPARSEABLE_CALL_LABEL, parsed.displayLabel)
        }
    }

    @Test
    fun summaryLinesAreStable() {
        assertEquals("Missed call", CallEventDescriptor(CallOutcome.NoAnswer).summaryLine())
        assertEquals("Video call", CallEventDescriptor(CallOutcome.Completed, video = true, durationSeconds = 4).summaryLine())
        assertTrue(CallEventDescriptor(CallOutcome.Busy).isValid())
        assertNotNull(MessageContentParser.parse(ChatProtocol.ChatCallEventMessageDataType, """{"outcome":"Busy"}"""))
    }
}
