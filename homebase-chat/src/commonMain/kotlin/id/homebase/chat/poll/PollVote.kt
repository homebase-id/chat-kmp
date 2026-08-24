package id.homebase.chat.poll

import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.common.OdinId
import id.homebase.chat.services.decodeReactionCode
import id.homebase.core.widget.EmojiReaction

/**
 * Encodes/decodes poll votes as chat reaction codes. A vote for option index
 * i is the reaction "_p$i" (≤8 chars, satisfies isValidEmoji). Mirror of
 * GroodleVote. See ADDING_TYPED_MESSAGE_KIND.md.
 *
 * The leading underscore marks the reaction as "not an emoji to render" — the
 * generic reaction UI filters out any reaction whose decoded text starts with
 * `_`, so vote codes never show up as pills. The Poll bubble renders the tally
 * itself.
 *
 * Code shape: `_p{optionIndex}` where optionIndex is 0-based. Examples: `_p0`,
 * `_p9`. Max 4 chars, well within the `length <= 8` reaction cap.
 */
object PollVote {
    private const val PREFIX = "_p"

    /** Encodes a 0-based option index into its reaction code. */
    fun codeFor(optionIndex: Int): String = "$PREFIX$optionIndex"

    /**
     * Decodes a raw reaction code to a 0-based option index, or null when it
     * doesn't match the poll vote grammar (stray emoji, other content, negative
     * index).
     */
    fun optionOf(code: String): Int? =
        if (code.startsWith(PREFIX)) code.removePrefix(PREFIX).toIntOrNull()?.takeIf { it >= 0 }
        else null

    /**
     * The current user's voted option indices, filtered to valid range
     * `[0, optionCount)`. The caller's own reactions are already decoded
     * reaction codes (not JSON-wrapped) for the own-reactions path.
     */
    fun ownVotes(ownReactions: Iterable<String>, optionCount: Int): Set<Int> =
        ownReactions.mapNotNull { optionOf(it) }.filter { it in 0 until optionCount }.toSet()

    /**
     * Aggregate vote count per option, read from the server-side reaction
     * preview. Returns an [IntArray] of length [optionCount] (zeroed when no
     * votes). Aggregate counts can't be de-duped per voter, so correctness
     * relies on the bubble's clear-then-set on vote change (same as Groodle).
     */
    fun counts(summary: ReactionSummary?, optionCount: Int): IntArray {
        val out = IntArray(optionCount)
        if (summary != null) {
            for ((_, entry) in summary.reactions) {
                val code = decodeReactionCode(entry.reactionContent) ?: continue
                val opt = optionOf(code) ?: continue
                if (opt in 0 until optionCount) out[opt] += entry.count
            }
        }
        return out
    }

    /**
     * Merges the two disagreeing sources the detail screen has to reconcile:
     * the header [ReactionSummary] (what the bubble counts — authoritative for
     * HOW MANY) and the fetched per-user [roster] (authoritative for WHO, but
     * possibly short — see `ChatMessageActionService.getReactions`).
     *
     * The reported count is `max(summaryCount, votersShown)` so the detail
     * screen can never claim fewer votes than the bubble, nor fewer than the
     * rows it is actually rendering. [selfOdinId] sorts first in every option's
     * voter list.
     */
    fun tally(
        summary: ReactionSummary?,
        roster: List<EmojiReaction>?,
        optionCount: Int,
        selfOdinId: OdinId?,
    ): List<PollOptionTally> {
        val counts = counts(summary, optionCount)
        val votersByOption = roster.orEmpty()
            .mapNotNull { reaction -> optionOf(reaction.emoji)?.let { it to reaction.odinId } }
            .filter { (option, _) -> option in 0 until optionCount }
            .groupBy({ it.first }, { it.second })

        return (0 until optionCount).map { option ->
            val voters = votersByOption[option].orEmpty()
                .distinct()
                .sortedByDescending { it == selfOdinId }
            PollOptionTally(
                index = option,
                count = maxOf(counts[option], voters.size),
                voters = voters,
            )
        }
    }
}

/**
 * One option's row in the poll detail roster.
 *
 * [count] comes from the header summary, [voters] from the live per-user read.
 * [isPartial] means we know about more votes than we can name — the screen must
 * say so rather than silently under-report the roster.
 */
data class PollOptionTally(
    val index: Int,
    val count: Int,
    val voters: List<OdinId>,
) {
    val isPartial: Boolean get() = voters.size < count
}
