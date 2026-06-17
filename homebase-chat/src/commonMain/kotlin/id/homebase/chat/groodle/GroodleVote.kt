package id.homebase.chat.groodle

import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.chat.services.decodeReactionCode

/**
 * Groodle votes are stored as ordinary chat reactions on the poll message,
 * encoded as a short ASCII code rather than an emoji. The leading underscore
 * marks the reaction as "not an emoji to render" — the generic reaction UI
 * ([id.homebase.core.widget.ReactionList] /
 * [id.homebase.core.widget.ReactionsBottomSheet]) filters out any reaction whose
 * decoded text starts with `_`, so vote codes never show up as pills. The
 * Groodle detail dialog renders the tally itself.
 *
 * Code shape: `_<slotIndex><Y|N|M>` where slotIndex is 1-based and matches the
 * descriptor's [GroodleDescriptor.slots] order (stable — the descriptor is never
 * edited after send). Examples: `_1Y`, `_3M`, `_10N`. Max 4 chars, within
 * [id.homebase.chat.services.ChatMessageActionService]'s `length <= 8` reaction
 * cap.
 *
 * Mirrors `event/EventRsvp.kt`, generalized to per-slot voting.
 */
object GroodleVote {

    enum class Choice(val letter: Char) {
        YES('Y'),
        NO('N'),
        MAYBE('M'),
    }

    private val CODE_REGEX = Regex("^_(\\d{1,2})([YNM])$")

    /** Encodes a 1-based slot index + choice into its reaction code. */
    fun encode(slotIndex1Based: Int, choice: Choice): String = "_$slotIndex1Based${choice.letter}"

    /**
     * Decodes a raw reaction code to (1-based slot index, choice), or null when it
     * doesn't match the vote grammar (stray emoji, other content). Pass [slotCount]
     * and [allowMaybe] to reject out-of-range slots and disallowed Maybe votes.
     */
    fun decode(code: String, slotCount: Int, allowMaybe: Boolean): Pair<Int, Choice>? {
        val match = CODE_REGEX.matchEntire(code) ?: return null
        val slot = match.groupValues[1].toIntOrNull() ?: return null
        if (slot !in 1..slotCount) return null
        val choice = when (match.groupValues[2]) {
            "Y" -> Choice.YES
            "N" -> Choice.NO
            "M" -> if (allowMaybe) Choice.MAYBE else return null
            else -> return null
        }
        return slot to choice
    }

    /**
     * Extracts the emoji/code string from a single `ownReactions` entry. The stored
     * shape is JSON-encoded ReactionContent (`{"emoji":"_1Y"}`). Delegates to the
     * shared [decodeReactionCode].
     */
    fun codeOf(rawReaction: String): String? = decodeReactionCode(rawReaction)

    /**
     * The current user's vote per slot, decoded from their own reactions. Last
     * write wins per slot (the detail dialog clears a prior vote before setting a
     * new one, so well-behaved clients have at most one per slot anyway).
     */
    fun myVotes(
        ownReactions: Iterable<String>,
        slotCount: Int,
        allowMaybe: Boolean,
    ): Map<Int, Choice> {
        val result = HashMap<Int, Choice>()
        for (raw in ownReactions) {
            val code = codeOf(raw) ?: raw
            val (slot, choice) = decode(code, slotCount, allowMaybe) ?: continue
            result[slot] = choice
        }
        return result
    }

    /** Aggregate Y/N/M counts for one slot. */
    data class SlotCounts(val yes: Int, val no: Int, val maybe: Int) {
        val total: Int get() = yes + no + maybe

        /** Y=2, M=1, N=0 — used to rank slots and highlight the winner. */
        val score: Int get() = yes * 2 + maybe
    }

    /**
     * Per-slot tally across all reactors, read from the server-side reaction
     * preview. Returns an entry for every slot in `1..slotCount` (zeroed when no
     * votes). Aggregate counts can't be de-duped per voter, so correctness relies
     * on the detail dialog's clear-then-set on vote change (same as Event RSVP).
     */
    fun counts(summary: ReactionSummary?, slotCount: Int, allowMaybe: Boolean): Map<Int, SlotCounts> {
        val yes = IntArray(slotCount + 1)
        val no = IntArray(slotCount + 1)
        val maybe = IntArray(slotCount + 1)
        if (summary != null) {
            for ((_, entry) in summary.reactions) {
                val code = codeOf(entry.reactionContent) ?: entry.key
                val (slot, choice) = decode(code, slotCount, allowMaybe) ?: continue
                when (choice) {
                    Choice.YES -> yes[slot] += entry.count
                    Choice.NO -> no[slot] += entry.count
                    Choice.MAYBE -> maybe[slot] += entry.count
                }
            }
        }
        return (1..slotCount).associateWith { SlotCounts(yes[it], no[it], maybe[it]) }
    }
}
