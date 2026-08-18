package id.homebase.chat.poll

import id.homebase.api.client.drives.files.ReactionEntry
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.core.widget.EmojiReaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class PollVoteTest {
    @Test fun code_round_trips() {
        assertEquals(0, PollVote.optionOf(PollVote.codeFor(0)))
        assertEquals(9, PollVote.optionOf(PollVote.codeFor(9)))
    }

    @Test fun non_poll_code_decodes_to_null() {
        assertNull(PollVote.optionOf("✅"))
        assertNull(PollVote.optionOf("_pX"))
    }

    @Test fun ownVotes_filters_out_of_range() {
        val own = listOf(PollVote.codeFor(0), PollVote.codeFor(5), "🎉")
        assertEquals(setOf(0), PollVote.ownVotes(own, optionCount = 1))
    }

    @Test fun counts_sums_per_option() {
        val summary = ReactionSummary(
            reactions = mapOf(
                "a" to ReactionEntry(key = "alice", count = 2, reactionContent = json(PollVote.codeFor(0))),
                "b" to ReactionEntry(key = "bob", count = 1, reactionContent = json(PollVote.codeFor(1))),
            )
        )
        val counts = PollVote.counts(summary, optionCount = 2)
        assertEquals(2, counts[0])
        assertEquals(1, counts[1])
    }

    // --- tally(): the detail screen's summary-vs-roster reconciliation (#1178) ---

    @Test fun tally_reports_summary_count_when_roster_is_empty() {
        // The exact #1178 shape: the bubble shows 3 from the header summary while the
        // live per-user read comes back empty. The detail screen must still say 3 and
        // flag the roster as partial — never "No votes".
        val tallies = PollVote.tally(
            summary = summaryOf(0 to 3),
            roster = emptyList(),
            optionCount = 3,
            selfOdinId = null,
        )
        assertEquals(3, tallies[0].count)
        assertEquals(emptyList(), tallies[0].voters)
        assertTrue(tallies[0].isPartial)
        assertEquals(0, tallies[1].count)
        assertFalse(tallies[1].isPartial)
    }

    @Test fun tally_treats_a_failed_roster_the_same_as_a_missing_one() {
        // null roster = load failed / still loading. Counts survive; nothing is invented.
        val tallies = PollVote.tally(
            summary = summaryOf(1 to 2),
            roster = null,
            optionCount = 2,
            selfOdinId = null,
        )
        assertEquals(2, tallies[1].count)
        assertTrue(tallies[1].isPartial)
    }

    @Test fun tally_is_never_lower_than_the_roster_it_renders() {
        // Header summary lags behind (peer reaction not yet in the preview): the count
        // must rise to the number of voters actually listed, not under-report them.
        val tallies = PollVote.tally(
            summary = summaryOf(0 to 1),
            roster = listOf(vote(0, "alice.dev"), vote(0, "bob.dev")),
            optionCount = 1,
            selfOdinId = null,
        )
        assertEquals(2, tallies[0].count)
        assertFalse(tallies[0].isPartial)
    }

    @Test fun tally_puts_self_first_and_dedupes() {
        val self = OdinId("me.dev")
        val tallies = PollVote.tally(
            summary = summaryOf(0 to 2),
            roster = listOf(vote(0, "alice.dev"), vote(0, "me.dev"), vote(0, "alice.dev")),
            optionCount = 1,
            selfOdinId = self,
        )
        assertEquals(listOf(self, OdinId("alice.dev")), tallies[0].voters)
    }

    @Test fun tally_ignores_votes_for_out_of_range_options() {
        val tallies = PollVote.tally(
            summary = null,
            roster = listOf(vote(5, "alice.dev"), vote(0, "bob.dev")),
            optionCount = 1,
            selfOdinId = null,
        )
        assertEquals(1, tallies.size)
        assertEquals(listOf(OdinId("bob.dev")), tallies[0].voters)
    }

    private fun summaryOf(vararg optionToCount: Pair<Int, Int>) = ReactionSummary(
        reactions = optionToCount.associate { (option, count) ->
            "k$option" to ReactionEntry(
                key = "k$option",
                count = count,
                reactionContent = json(PollVote.codeFor(option)),
            )
        }
    )

    @OptIn(ExperimentalUuidApi::class)
    private fun vote(option: Int, odinId: String) = EmojiReaction(
        messageId = Uuid.NIL,
        emoji = PollVote.codeFor(option),
        odinId = OdinId(odinId),
        created = UnixTimeUtc.ZeroTime,
    )

    private fun json(code: String) = """{"emoji":"$code"}"""
}
