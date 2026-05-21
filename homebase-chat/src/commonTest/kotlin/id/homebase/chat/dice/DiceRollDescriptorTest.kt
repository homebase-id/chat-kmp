package id.homebase.chat.dice

import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.uuid.Uuid

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
    fun summary_line_formats_NdF_for_standalone() {
        val descriptor = DiceRollDescriptor(
            faces = 6,
            rolls = listOf(chainRoll(messageId = Uuid.random(), odinId = "alice.test", results = listOf(3, 5, 1, 4))),
        )
        assertEquals(13, descriptor.sum)
        assertEquals("Rolled 13 (4d6)", descriptor.summaryLine())
        assertFalse(descriptor.isBattle)
    }

    @Test
    fun summary_line_says_Battle_when_chain_has_two_or_more() {
        val descriptor = battleDescriptor(
            faces = 20,
            entries = listOf(
                "alice.test" to listOf(4, 17, 8),
                "bob.test" to listOf(12, 18, 11),
            ),
        )
        assertTrue(descriptor.isBattle)
        assertEquals(41, descriptor.sum)  // bob's 12+18+11
        assertEquals("Battle: rolled 41", descriptor.summaryLine())
    }

    @Test
    fun isValid_rejects_disallowed_face_count() {
        val descriptor = standaloneDescriptor(faces = 7, results = listOf(1, 2, 3))
        assertFalse(descriptor.isValid())
    }

    @Test
    fun isValid_rejects_empty_rolls() {
        val descriptor = DiceRollDescriptor(faces = 6, rolls = emptyList())
        assertFalse(descriptor.isValid())
    }

    @Test
    fun isValid_rejects_out_of_range_value() {
        val descriptor = standaloneDescriptor(faces = 6, results = listOf(7))
        assertFalse(descriptor.isValid())
    }

    @Test
    fun isValid_rejects_too_many_dice() {
        val descriptor = standaloneDescriptor(
            faces = 6,
            results = List(DiceRollDescriptor.MAX_DICE + 1) { 3 },
        )
        assertFalse(descriptor.isValid())
    }

    @Test
    fun isValid_rejects_chain_exceeding_max_participants() {
        val entries = (1..DiceRollDescriptor.MAX_BATTLE_PARTICIPANTS + 1).map {
            "p$it.test" to listOf(it.coerceAtMost(20))
        }
        val descriptor = battleDescriptor(faces = 20, entries = entries)
        assertFalse(descriptor.isValid())
    }

    @Test
    fun isValid_rejects_duplicate_odinIds_in_chain() {
        val descriptor = battleDescriptor(
            faces = 20,
            entries = listOf(
                "alice.test" to listOf(10),
                "bob.test" to listOf(15),
                "alice.test" to listOf(20),  // alice rebattled — not allowed
            ),
        )
        assertFalse(descriptor.isValid())
    }

    @Test
    fun isValid_rejects_chain_with_inconsistent_dice_count() {
        // First entry has 2 dice, second has 3 — chain should lock the dice config.
        val descriptor = DiceRollDescriptor(
            faces = 20,
            rolls = listOf(
                chainRoll(messageId = Uuid.random(), odinId = "alice.test", results = listOf(10, 5), rolledAtUtcMs = 1L),
                chainRoll(messageId = Uuid.random(), odinId = "bob.test", results = listOf(8, 12, 3), rolledAtUtcMs = 2L),
            ),
        )
        assertFalse(descriptor.isValid())
    }

    @Test
    fun isValid_rejects_non_chronological_chain() {
        val descriptor = DiceRollDescriptor(
            faces = 20,
            rolls = listOf(
                chainRoll(messageId = Uuid.random(), odinId = "alice.test", results = listOf(10), rolledAtUtcMs = 1000L),
                chainRoll(messageId = Uuid.random(), odinId = "bob.test", results = listOf(15), rolledAtUtcMs = 500L),
            ),
        )
        assertFalse(descriptor.isValid())
    }

    @Test
    fun isValid_accepts_max_participants_chain() {
        val entries = (1..DiceRollDescriptor.MAX_BATTLE_PARTICIPANTS).map {
            "p$it.test" to listOf(it.coerceAtMost(20))
        }
        val descriptor = battleDescriptor(faces = 20, entries = entries)
        assertTrue(descriptor.isValid(), "chain at exactly MAX should be valid")
    }

    @Test
    fun parser_round_trips_descriptor_through_messagecontent() {
        val original = standaloneDescriptor(faces = 20, results = listOf(20, 1, 10, 7))
        val serialized = MessageContentParser.serialize(MessageContent.DiceRoll(original))
        val parsed = MessageContentParser.parse(
            ChatProtocol.ChatDiceRollMessageDataType,
            serialized,
        )
        val diceRoll = assertIs<MessageContent.DiceRoll>(parsed)
        val descriptor = assertNotNull(diceRoll.descriptor)
        assertEquals(original, descriptor)
        assertEquals(38, descriptor.sum)
        assertEquals("Rolled 38 (4d20)", descriptor.summaryLine())
    }

    @Test
    fun parser_round_trips_battle_descriptor() {
        val original = battleDescriptor(
            faces = 20,
            entries = listOf(
                "alice.test" to listOf(4, 17, 8),
                "bob.test" to listOf(12, 18, 11),
                "carol.test" to listOf(20, 15, 14),
            ),
        )
        val serialized = MessageContentParser.serialize(MessageContent.DiceRoll(original))
        val parsed = MessageContentParser.parse(
            ChatProtocol.ChatDiceRollMessageDataType,
            serialized,
        )
        val diceRoll = assertIs<MessageContent.DiceRoll>(parsed)
        val descriptor = assertNotNull(diceRoll.descriptor)
        assertEquals(original, descriptor)
        assertEquals(3, descriptor.rolls.size)
        assertTrue(descriptor.isBattle)
    }

    @Test
    fun parser_returns_dice_with_null_descriptor_for_invalid_payload() {
        // Hand-crafted JSON with disallowed `faces` value — parser must reject
        // it as a valid descriptor but still surface the message as a DiceRoll
        // kind (with a null descriptor) so MessageBubbleRaw renders the
        // unsupported-format chip rather than dropping the message into the
        // text-fallback path.
        val bogus = OdinSystemSerializer.serialize(
            standaloneDescriptor(faces = 7, results = listOf(1, 2, 3)),
        )
        val parsed = MessageContentParser.parse(
            ChatProtocol.ChatDiceRollMessageDataType,
            bogus,
        )
        val diceRoll = assertIs<MessageContent.DiceRoll>(parsed)
        assertNull(diceRoll.descriptor)
        assertEquals(MessageContent.UNPARSEABLE_DICE_LABEL, diceRoll.displayLabel)
    }

    @Test
    fun parser_dataTypeFor_returns_dice_constant() {
        val descriptor = standaloneDescriptor()
        val dt = MessageContentParser.dataTypeFor(MessageContent.DiceRoll(descriptor))
        assertEquals(ChatProtocol.ChatDiceRollMessageDataType, dt)
    }

    @Test
    fun parser_displayLabel_uses_summary_line() {
        val descriptor = standaloneDescriptor(faces = 12, results = listOf(11, 12, 6))
        val content = MessageContent.DiceRoll(descriptor)
        assertEquals("Rolled 29 (3d12)", content.displayLabel)
    }

    // ---- Open-ended d100 ----

    @Test
    fun parser_accepts_legacy_json_without_mode_field() {
        // Hand-crafted JSON exactly as a pre-OE client would have produced it
        // (no `mode` key). The deserializer must apply the default
        // `DiceRollMode.Standard` and the descriptor must still pass isValid()
        // — otherwise the long-press menu's Duel entry would disappear on
        // every pre-existing roll.
        val legacyJson = """
            {
              "faces": 20,
              "rolls": [
                {
                  "messageId": "11111111-1111-1111-1111-111111111111",
                  "odinId": "alice.test",
                  "results": [15, 12],
                  "rolledAtUtcMs": 1700000000000,
                  "source": "LocalRandom"
                }
              ],
              "schemaVersion": 1
            }
        """.trimIndent()
        val parsed = MessageContentParser.parse(
            ChatProtocol.ChatDiceRollMessageDataType,
            legacyJson,
        )
        val diceRoll = assertIs<MessageContent.DiceRoll>(parsed)
        val descriptor = assertNotNull(diceRoll.descriptor)
        assertEquals(DiceRollMode.Standard, descriptor.mode)
        assertTrue(descriptor.isValid())
        assertEquals(27, descriptor.sum)
    }

    @Test
    fun summary_line_for_oe_standalone_says_1d100OE() {
        // 10,10 → 100 (UP), 4,7 → 47 (stops). Sum = 100 + 47 = 147.
        val descriptor = oeStandalone(results = listOf(10, 10, 4, 7))
        assertEquals(147, descriptor.sum)
        assertEquals("Rolled 147 (1d100OE)", descriptor.summaryLine())
    }

    @Test
    fun summary_line_for_negative_oe_is_signed() {
        // 0,3 → 3 (DOWN), 5,0 → 50. Sum = 3 - 50 = -47.
        val descriptor = oeStandalone(results = listOf(10, 3, 5, 10))
        assertEquals(-47, descriptor.sum)
        assertEquals("Rolled -47 (1d100OE)", descriptor.summaryLine())
    }

    @Test
    fun isValid_accepts_oe_chain_with_differing_per_entry_sizes() {
        // alice rolled 1 pair (47), bob rolled a 3-pair chain (200).
        val descriptor = DiceRollDescriptor(
            faces = 10,
            mode = DiceRollMode.OpenEndedD100,
            rolls = listOf(
                chainRoll(messageId = Uuid.random(), odinId = "alice.test", results = listOf(4, 7), rolledAtUtcMs = 1L),
                chainRoll(messageId = Uuid.random(), odinId = "bob.test", results = listOf(10, 10, 9, 8, 10, 2), rolledAtUtcMs = 2L),
            ),
        )
        assertTrue(descriptor.isValid())
    }

    @Test
    fun isValid_rejects_oe_with_odd_length_results() {
        val descriptor = oeStandalone(results = listOf(10, 10, 5))
        assertFalse(descriptor.isValid())
    }

    @Test
    fun isValid_rejects_oe_with_faces_not_ten() {
        val descriptor = DiceRollDescriptor(
            faces = 20,
            mode = DiceRollMode.OpenEndedD100,
            rolls = listOf(chainRoll(messageId = Uuid.random(), odinId = "alice.test", results = listOf(5, 5))),
        )
        assertFalse(descriptor.isValid())
    }

    @Test
    fun isValid_rejects_oe_with_zero_in_results() {
        // Storage is 1..10; a literal `0` is not a legal d10 face value.
        val descriptor = oeStandalone(results = listOf(0, 2))
        assertFalse(descriptor.isValid())
    }

    @Test
    fun parser_round_trips_oe_descriptor() {
        val original = oeStandalone(results = listOf(10, 10, 9, 8, 10, 2))
        val serialized = MessageContentParser.serialize(MessageContent.DiceRoll(original))
        val parsed = MessageContentParser.parse(
            ChatProtocol.ChatDiceRollMessageDataType,
            serialized,
        )
        val diceRoll = assertIs<MessageContent.DiceRoll>(parsed)
        val descriptor = assertNotNull(diceRoll.descriptor)
        assertEquals(DiceRollMode.OpenEndedD100, descriptor.mode)
        assertEquals(original, descriptor)
        assertEquals(200, descriptor.sum)
        assertEquals("Rolled 200 (1d100OE)", descriptor.summaryLine())
    }

    private fun oeStandalone(results: List<Int>): DiceRollDescriptor = DiceRollDescriptor(
        faces = 10,
        mode = DiceRollMode.OpenEndedD100,
        rolls = listOf(chainRoll(messageId = Uuid.random(), odinId = "alice.test", results = results)),
    )

    // ---- helpers ----

    private fun chainRoll(
        messageId: Uuid,
        odinId: String,
        results: List<Int>,
        rolledAtUtcMs: Long = 1_700_000_000_000L,
    ): ChainRoll = ChainRoll(
        messageId = messageId,
        odinId = OdinId(odinId),
        results = results,
        rolledAtUtcMs = rolledAtUtcMs,
    )

    private fun standaloneDescriptor(
        faces: Int = 6,
        results: List<Int> = listOf(1, 2, 3),
    ): DiceRollDescriptor = DiceRollDescriptor(
        faces = faces,
        rolls = listOf(chainRoll(messageId = Uuid.random(), odinId = "alice.test", results = results)),
    )

    private fun battleDescriptor(
        faces: Int,
        entries: List<Pair<String, List<Int>>>,
    ): DiceRollDescriptor {
        val rolls = entries.mapIndexed { idx, (odinId, results) ->
            chainRoll(
                messageId = Uuid.random(),
                odinId = odinId,
                results = results,
                rolledAtUtcMs = 1_700_000_000_000L + idx,
            )
        }
        return DiceRollDescriptor(faces = faces, rolls = rolls)
    }
}
