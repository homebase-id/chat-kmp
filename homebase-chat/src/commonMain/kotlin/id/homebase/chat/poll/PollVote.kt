package id.homebase.chat.poll

import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.serialization.OdinSystemSerializer

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
                val code = codeOf(entry.reactionContent) ?: continue
                val opt = optionOf(code) ?: continue
                if (opt in 0 until optionCount) out[opt] += entry.count
            }
        }
        return out
    }

    /**
     * Extracts the emoji/code string from a single reaction entry's
     * `reactionContent`. The stored shape is JSON-encoded [ReactionContent]
     * (`{"emoji":"_p0"}`). Copied verbatim from GroodleVote.codeOf.
     */
    private fun codeOf(reactionContent: String): String? = runCatching {
        OdinSystemSerializer.deserialize<ReactionContent>(reactionContent).emoji
    }.getOrNull()
}
