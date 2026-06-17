package id.homebase.chat.poll

import id.homebase.api.client.drives.files.ReactionEntry
import id.homebase.api.client.drives.files.ReactionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    private fun json(code: String) = """{"emoji":"$code"}"""
}
