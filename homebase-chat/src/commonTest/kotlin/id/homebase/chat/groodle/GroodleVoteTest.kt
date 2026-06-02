package id.homebase.chat.groodle

import id.homebase.api.client.drives.files.ReactionEntry
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroodleVoteTest {

    private fun reactionJson(code: String): String =
        OdinSystemSerializer.serialize(ReactionContent(emoji = code))

    private fun summaryOf(vararg codeToCount: Pair<String, Int>): ReactionSummary {
        val map = codeToCount.associate { (code, count) ->
            code to ReactionEntry(key = code, count = count, reactionContent = reactionJson(code))
        }
        return ReactionSummary(reactions = map)
    }

    @Test
    fun encode_produces_expected_codes() {
        assertEquals("_1Y", GroodleVote.encode(1, GroodleVote.Choice.YES))
        assertEquals("_3M", GroodleVote.encode(3, GroodleVote.Choice.MAYBE))
        assertEquals("_10N", GroodleVote.encode(10, GroodleVote.Choice.NO))
    }

    @Test
    fun decode_round_trips_valid_codes() {
        assertEquals(1 to GroodleVote.Choice.YES, GroodleVote.decode("_1Y", slotCount = 10, allowMaybe = true))
        assertEquals(10 to GroodleVote.Choice.NO, GroodleVote.decode("_10N", slotCount = 10, allowMaybe = true))
        assertEquals(3 to GroodleVote.Choice.MAYBE, GroodleVote.decode("_3M", slotCount = 10, allowMaybe = true))
    }

    @Test
    fun decode_rejects_maybe_when_disallowed() {
        assertNull(GroodleVote.decode("_3M", slotCount = 10, allowMaybe = false))
        // Y/N still fine.
        assertEquals(3 to GroodleVote.Choice.YES, GroodleVote.decode("_3Y", slotCount = 10, allowMaybe = false))
    }

    @Test
    fun decode_rejects_out_of_range_slot() {
        assertNull(GroodleVote.decode("_0Y", slotCount = 10, allowMaybe = true))
        assertNull(GroodleVote.decode("_11Y", slotCount = 10, allowMaybe = true))
    }

    @Test
    fun decode_rejects_stray_strings_and_emoji() {
        assertNull(GroodleVote.decode("1Y", slotCount = 10, allowMaybe = true))   // no underscore
        assertNull(GroodleVote.decode("_1X", slotCount = 10, allowMaybe = true))  // bad choice
        assertNull(GroodleVote.decode("😀", slotCount = 10, allowMaybe = true))
        assertNull(GroodleVote.decode("_", slotCount = 10, allowMaybe = true))
        assertNull(GroodleVote.decode("_1", slotCount = 10, allowMaybe = true))
    }

    @Test
    fun counts_tally_and_score_per_slot() {
        val summary = summaryOf("_1Y" to 2, "_1N" to 1, "_2M" to 3)
        val counts = GroodleVote.counts(summary, slotCount = 2, allowMaybe = true)

        val slot1 = counts.getValue(1)
        assertEquals(GroodleVote.SlotCounts(yes = 2, no = 1, maybe = 0), slot1)
        assertEquals(4, slot1.score) // 2*2 + 0

        val slot2 = counts.getValue(2)
        assertEquals(GroodleVote.SlotCounts(yes = 0, no = 0, maybe = 3), slot2)
        assertEquals(3, slot2.score) // 0*2 + 3
    }

    @Test
    fun counts_ignores_maybe_when_disallowed() {
        val summary = summaryOf("_1Y" to 1, "_1M" to 5)
        val counts = GroodleVote.counts(summary, slotCount = 1, allowMaybe = false)
        assertEquals(GroodleVote.SlotCounts(yes = 1, no = 0, maybe = 0), counts.getValue(1))
    }

    @Test
    fun counts_returns_zeroed_entries_for_null_summary() {
        val counts = GroodleVote.counts(null, slotCount = 3, allowMaybe = true)
        assertEquals(3, counts.size)
        assertEquals(GroodleVote.SlotCounts(0, 0, 0), counts.getValue(2))
    }

    @Test
    fun my_votes_decodes_own_reactions_last_write_wins() {
        val own = listOf(reactionJson("_1Y"), reactionJson("_2N"), reactionJson("_3M"))
        val votes = GroodleVote.myVotes(own, slotCount = 3, allowMaybe = true)
        assertEquals(
            mapOf(1 to GroodleVote.Choice.YES, 2 to GroodleVote.Choice.NO, 3 to GroodleVote.Choice.MAYBE),
            votes,
        )
    }

    @Test
    fun my_votes_drops_maybe_when_disallowed() {
        val own = listOf(reactionJson("_1Y"), reactionJson("_3M"))
        val votes = GroodleVote.myVotes(own, slotCount = 3, allowMaybe = false)
        assertEquals(mapOf(1 to GroodleVote.Choice.YES), votes)
    }
}
