package id.homebase.chat.groodle

import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroodleDescriptorTest {

    private fun base(
        title: String = "Q2 Planning Sync",
        description: String = "Pick the times that work for you.",
        timezone: String = "Europe/Copenhagen",
        allowMaybe: Boolean = true,
        deadlineUtcMs: Long? = null,
        slots: List<GroodleSlot> = listOf(
            GroodleSlot(startUtcMs = 1_747_929_600_000L, endUtcMs = 1_747_933_200_000L),
            GroodleSlot(startUtcMs = 1_748_016_000_000L, endUtcMs = 1_748_019_600_000L),
        ),
    ) = GroodleDescriptor(
        title = title,
        description = description,
        timezone = timezone,
        allowMaybe = allowMaybe,
        deadlineUtcMs = deadlineUtcMs,
        slots = slots,
    )

    @Test
    fun valid_descriptor_passes_and_summary_is_title() {
        val d = base()
        assertTrue(d.isValid())
        assertEquals("Q2 Planning Sync", d.summaryLine())
    }

    @Test
    fun rejects_blank_title() {
        assertFalse(base(title = "   ").isValid())
    }

    @Test
    fun rejects_title_over_80_codepoints() {
        assertFalse(base(title = "a".repeat(81)).isValid())
        assertTrue(base(title = "a".repeat(80)).isValid())
    }

    @Test
    fun rejects_description_over_280_codepoints() {
        assertFalse(base(description = "a".repeat(281)).isValid())
        assertTrue(base(description = "a".repeat(280)).isValid())
    }

    @Test
    fun rejects_blank_timezone() {
        assertFalse(base(timezone = "  ").isValid())
    }

    @Test
    fun rejects_empty_slots() {
        assertFalse(base(slots = emptyList()).isValid())
    }

    @Test
    fun rejects_more_than_ten_slots() {
        val eleven = (0 until 11).map { GroodleSlot(startUtcMs = 1_000_000L + it * 1_000L) }
        assertFalse(base(slots = eleven, deadlineUtcMs = null).isValid())
        val ten = (0 until 10).map { GroodleSlot(startUtcMs = 1_000_000L + it * 1_000L) }
        assertTrue(base(slots = ten, deadlineUtcMs = null).isValid())
    }

    @Test
    fun rejects_unsorted_slots() {
        val unsorted = listOf(
            GroodleSlot(startUtcMs = 2_000_000L),
            GroodleSlot(startUtcMs = 1_000_000L),
        )
        assertFalse(base(slots = unsorted).isValid())
    }

    @Test
    fun rejects_identical_slots() {
        val dup = listOf(
            GroodleSlot(startUtcMs = 1_000_000L, endUtcMs = 2_000_000L),
            GroodleSlot(startUtcMs = 1_000_000L, endUtcMs = 2_000_000L),
        )
        assertFalse(base(slots = dup).isValid())
    }

    @Test
    fun allows_same_start_different_end() {
        val sameStart = listOf(
            GroodleSlot(startUtcMs = 1_000_000L, endUtcMs = 2_000_000L),
            GroodleSlot(startUtcMs = 1_000_000L, endUtcMs = 3_000_000L),
        )
        assertTrue(base(slots = sameStart).isValid())
    }

    @Test
    fun rejects_end_before_or_equal_start() {
        assertFalse(base(slots = listOf(GroodleSlot(startUtcMs = 1_000L, endUtcMs = 1_000L))).isValid())
        assertFalse(base(slots = listOf(GroodleSlot(startUtcMs = 2_000L, endUtcMs = 1_000L))).isValid())
    }

    @Test
    fun rejects_deadline_after_first_slot() {
        val firstStart = 1_747_929_600_000L
        assertFalse(base(deadlineUtcMs = firstStart + 1).isValid())
        assertTrue(base(deadlineUtcMs = firstStart).isValid())
        assertTrue(base(deadlineUtcMs = firstStart - 86_400_000L).isValid())
    }

    @Test
    fun rejects_schema_version_below_one() {
        assertFalse(base().copy(schemaVersion = 0).isValid())
    }

    @Test
    fun round_trips_through_parser() {
        val original = base(deadlineUtcMs = 1_747_843_200_000L)
        val serialized = MessageContentParser.serialize(MessageContent.Groodle(original))
        val parsed = MessageContentParser.parse(ChatProtocol.ChatGroodleMessageDataType, serialized)
        val groodle = assertIs<MessageContent.Groodle>(parsed)
        assertEquals(original, groodle.descriptor)
    }

    @Test
    fun invalid_descriptor_parses_to_null_not_dropped() {
        // Serializes fine but fails validation (empty slots).
        val bogus = OdinSystemSerializer.serialize(
            base(slots = emptyList()),
        )
        val parsed = MessageContentParser.parse(ChatProtocol.ChatGroodleMessageDataType, bogus)
        val groodle = assertIs<MessageContent.Groodle>(parsed)
        assertNull(groodle.descriptor)
        assertEquals(MessageContent.UNPARSEABLE_GROODLE_LABEL, groodle.displayLabel)
    }

    @Test
    fun malformed_json_parses_to_null_not_dropped() {
        val parsed = MessageContentParser.parse(ChatProtocol.ChatGroodleMessageDataType, "{not valid json")
        val groodle = assertIs<MessageContent.Groodle>(parsed)
        assertNull(groodle.descriptor)
    }

    @Test
    fun data_type_for_is_213() {
        assertEquals(
            ChatProtocol.ChatGroodleMessageDataType,
            MessageContentParser.dataTypeFor(MessageContent.Groodle(base())),
        )
    }
}
