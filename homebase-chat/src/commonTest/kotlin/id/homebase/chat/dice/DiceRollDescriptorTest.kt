package id.homebase.chat.dice

import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiceRollDescriptorTest {

    @Test
    fun roll_returns_count_results_in_face_range() {
        for (faces in DiceRollDescriptor.ALLOWED_FACES) {
            val results = roll(count = 5, faces = faces, shakeSamples = null)
            assertEquals(5, results.size, "5 dice for d$faces")
            assertTrue(results.all { it in 1..faces }, "all values in 1..$faces, got $results")
        }
    }

    @Test
    fun roll_with_shake_samples_uses_seeded_rng_and_stays_in_range() {
        val samples = listOf(0x1234567890abcdefL, 0xFEDCBA9876543210u.toLong(), 42L)
        val results = roll(count = 4, faces = 20, shakeSamples = samples)
        assertEquals(4, results.size)
        assertTrue(results.all { it in 1..20 })
    }

    @Test
    fun summary_line_formats_NdF() {
        val descriptor = DiceRollDescriptor(
            rollId = "abc", faces = 6,
            results = listOf(3, 5, 1, 4),
            rolledByOdinId = "alice.odin", rolledAtUtcMs = 0L,
        )
        assertEquals(13, descriptor.sum)
        assertEquals("Rolled 13 (4d6)", descriptor.summaryLine())
    }

    @Test
    fun isValid_rejects_disallowed_face_count() {
        val descriptor = baseDescriptor().copy(faces = 7, results = listOf(1, 2, 3))
        assertTrue(!descriptor.isValid())
    }

    @Test
    fun isValid_rejects_empty_results() {
        val descriptor = baseDescriptor().copy(results = emptyList())
        assertTrue(!descriptor.isValid())
    }

    @Test
    fun isValid_rejects_out_of_range_value() {
        val descriptor = baseDescriptor().copy(faces = 6, results = listOf(7))
        assertTrue(!descriptor.isValid())
    }

    @Test
    fun isValid_rejects_too_many_dice() {
        val descriptor = baseDescriptor().copy(
            faces = 6,
            results = List(DiceRollDescriptor.MAX_DICE + 1) { 3 },
        )
        assertTrue(!descriptor.isValid())
    }

    @Test
    fun parser_round_trips_descriptor_through_messagecontent() {
        val original = baseDescriptor().copy(faces = 20, results = listOf(20, 1, 10, 7))
        val serialized = MessageContentParser.serialize(MessageContent.DiceRoll(original))
        val parsed = MessageContentParser.parse(
            ChatProtocol.ChatDiceRollMessageDataType,
            serialized,
        )
        assertNotNull(parsed)
        assertTrue(parsed is MessageContent.DiceRoll)
        assertEquals(original, parsed.descriptor)
        assertEquals(38, parsed.descriptor.sum)
        assertEquals("Rolled 38 (4d20)", parsed.descriptor.summaryLine())
    }

    @Test
    fun parser_returns_null_for_invalid_descriptor() {
        // Hand-crafted JSON with disallowed `faces` value — parser must reject
        // without throwing so the message stream falls back to the unparseable
        // bubble instead of crashing.
        val bogus = OdinSystemSerializer.serialize(
            baseDescriptor().copy(faces = 7, results = listOf(1, 2, 3)),
        )
        val parsed = MessageContentParser.parse(
            ChatProtocol.ChatDiceRollMessageDataType,
            bogus,
        )
        assertNull(parsed)
    }

    @Test
    fun parser_dataTypeFor_returns_dice_constant() {
        val descriptor = baseDescriptor()
        val dt = MessageContentParser.dataTypeFor(MessageContent.DiceRoll(descriptor))
        assertEquals(ChatProtocol.ChatDiceRollMessageDataType, dt)
    }

    @Test
    fun parser_displayLabel_uses_summary_line() {
        val descriptor = baseDescriptor().copy(faces = 12, results = listOf(11, 12, 6))
        val content = MessageContent.DiceRoll(descriptor)
        assertEquals("Rolled 29 (3d12)", content.displayLabel)
    }

    private fun baseDescriptor(): DiceRollDescriptor = DiceRollDescriptor(
        rollId = "abc",
        faces = 6,
        results = listOf(1, 2, 3),
        rolledByOdinId = "alice.odin",
        rolledAtUtcMs = 1_700_000_000_000L,
    )
}
