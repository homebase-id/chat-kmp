package id.homebase.chat.event

import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ReactionSetChange

/**
 * RSVPs are stored as ordinary chat reactions on the event message — three
 * specific emoji codepoints. The detail screen surfaces them as big buttons;
 * a tap declares the whole RSVP state at once via [change] (so a user can't
 * simultaneously be "going" and "not going").
 *
 * Source of truth for the *current user's* RSVP is [MessageUiModel.ownReactions]
 * — already mirrored from `localAppData.localReactions`. The aggregate counts
 * for a group rollup come from [MessageUiModel.reactionPreview].
 */
object EventRsvp {
    // Picked for tone: ✅ / 🤔 / ❌ reads as confirm / consider / decline,
    // less judgmental than 👍 / 🤔 / 👎 (a thumbs-down on a friend's
    // birthday party shouldn't be the visual). Existing RSVPs sent before
    // this change carry the old emojis and won't be counted in rollups
    // or recognized as "current RSVP" by new clients — accepted, no
    // backfill.
    const val GOING = "✅"
    const val NOT_GOING = "❌"
    const val MAYBE = "🤔"

    val ALL: Set<String> = setOf(GOING, NOT_GOING, MAYBE)

    /**
     * Extracts the emoji codepoint from a single `ownReactions` entry. The
     * stored shape is JSON-encoded [ReactionContent] (`{"emoji":"..."}`).
     */
    fun emojiOf(rawReaction: String): String? = runCatching {
        OdinSystemSerializer.deserialize<ReactionContent>(rawReaction).emoji
    }.getOrNull()

    /** Returns the user's current RSVP emoji, or null if none. */
    fun currentRsvp(ownReactions: Iterable<String>): String? =
        ownReactions.firstOrNull { it in ALL }

    /**
     * The reaction change a tap on [tapped] declares: tapping the current RSVP
     * retracts it (all three cleared), anything else sets it and clears the
     * other two. Always covers all three so stale state can't leave a ghost.
     */
    fun change(currentRsvp: String?, tapped: String): ReactionSetChange {
        require(tapped in ALL) { "not an RSVP emoji: $tapped" }
        return if (currentRsvp == tapped) {
            ReactionSetChange(scope = SCOPE, add = emptySet(), remove = ALL)
        } else {
            ReactionSetChange(scope = SCOPE, add = setOf(tapped), remove = ALL - tapped)
        }
    }

    private const val SCOPE = "rsvp"

    /**
     * Aggregate count of each RSVP across all reactors, read from the
     * server-side reaction preview. Returns 0s when [summary] is null.
     */
    data class Counts(val going: Int, val notGoing: Int, val maybe: Int)

    fun counts(summary: ReactionSummary?): Counts {
        if (summary == null) return Counts(0, 0, 0)
        var going = 0
        var notGoing = 0
        var maybe = 0
        for ((_, entry) in summary.reactions) {
            when (emojiOf(entry.reactionContent)) {
                GOING -> going += entry.count
                NOT_GOING -> notGoing += entry.count
                MAYBE -> maybe += entry.count
            }
        }
        return Counts(going, notGoing, maybe)
    }
}
