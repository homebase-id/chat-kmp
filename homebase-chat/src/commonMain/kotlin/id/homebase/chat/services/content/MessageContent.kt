package id.homebase.chat.services.content

import id.homebase.chat.dice.DiceRollDescriptor
import id.homebase.chat.event.EventDescriptor

/**
 * Typed rich-content riding on the message header (parsed from `dataType` +
 * `appData.content`). Distinct from regular text/media messages, which keep
 * their existing path through [id.homebase.chat.services.MessageAppData].
 *
 * Adding a new kind (poll, doodle, sticker, …): follow the full recipe in
 * `ADDING_TYPED_MESSAGE_KIND.md` at the repo root. The default [actions]
 * policy is [ActionPolicy.StructuredOneShot] — override only if your kind
 * needs edit/reply/forward/inline-reactions.
 */
sealed interface MessageContent {

    /**
     * Chat-action surface this content kind exposes in the message bubble's
     * long-press menu and hover affordances. Subtypes pick a profile, the
     * dispatchers (`MessageItem`, `Menu`) read booleans — no `is X`
     * type-checks scattered through the UI layer.
     */
    val actions: ActionPolicy get() = ActionPolicy.StructuredOneShot

    /**
     * Short human-readable label used everywhere the chat needs a fallback
     * "what was this message?": push notifications, conversation-list previews,
     * search index, the sender's `notificationText`. Each kind contributes its
     * own concise summary (event title, poll question, etc.). Never blank.
     */
    val displayLabel: String

    /**
     * A scheduled event with optional location, meeting URL, and RSVP via reactions.
     *
     * [descriptor] is nullable so that a malformed/unknown-version event message
     * (parser couldn't decode the JSON, or [EventDescriptor.isValid] would fail)
     * still surfaces as an Event of unknown shape — the bubble renders the
     * "unsupported format" chip rather than the message disappearing.
     */
    data class Event(val descriptor: EventDescriptor?) : MessageContent {
        override val actions: ActionPolicy = ActionPolicy(
            allowEdit = false,            // events are immutable announcements
            allowReply = true,            // organizer pings, RSVP nudges, follow-up Qs
            allowForward = false,         // forwarding an event out of context loses RSVP
            allowShare = false,           // copy-text of "Title @ time" adds little
            allowInlineReactions = false, // RSVP happens in the detail dialog
            allowReactionDetails = false, // RSVP rollup is the dialog's roster
        )
        override val displayLabel: String get() = descriptor?.title ?: UNPARSEABLE_EVENT_LABEL
    }

    /**
     * A dice roll: chosen die size, results, sum. Renders face images + sum line.
     * [descriptor] follows the same nullability contract as [Event.descriptor].
     */
    data class DiceRoll(val descriptor: DiceRollDescriptor?) : MessageContent {
        override val actions: ActionPolicy = ActionPolicy(
            allowEdit = false,           // rolls are immutable records of what happened
            allowReply = true,           // people quote-reply rolls in conversation
            allowForward = false,        // a roll without its conversation is meaningless
            allowShare = false,          // copy-text of "Rolled 30 (3d10)" adds little
            allowInlineReactions = true, // emoji reactions on rolls — yes
            allowReactionDetails = true, // who reacted with what is interesting
        )
        override val displayLabel: String get() = descriptor?.summaryLine() ?: UNPARSEABLE_DICE_LABEL
    }

    /**
     * A typed message whose [dataType] this client doesn't recognize — typically
     * a newer kind (poll, doodle, …) sent from a more up-to-date peer. The
     * receiver can't render the content itself; the bubble shows an
     * "update the app" chip with the dataType for diagnostics. Distinct from
     * [Event] / [DiceRoll] with a null descriptor: there we know the *kind*
     * but failed to parse it; here we don't even know what kind it is.
     */
    data class Unknown(val dataType: Int) : MessageContent {
        override val displayLabel: String get() = UNKNOWN_LABEL
    }

    companion object {
        // Plain-English fallbacks used by displayLabel (push notifications,
        // conversation-list previews, search index) when the descriptor failed
        // to parse. The bubble itself uses the localized chat_*_unparseable
        // string from compose resources, but those resources are only reachable
        // from a @Composable; this getter must work outside composition.
        const val UNPARSEABLE_EVENT_LABEL = "Event"
        const val UNPARSEABLE_DICE_LABEL = "Dice roll"
        const val UNKNOWN_LABEL = "Unknown message"
    }

}

/**
 * Per-message-kind affordance flags. Lets the UI dispatcher gate every
 * action on a boolean instead of `when (content)` ladders that grow each
 * time a new content kind lands.
 *
 * [Standard] = full chat-action surface (text + media messages).
 * [StructuredOneShot] = locked down to kind-specific interaction (events
 * RSVP via the detail dialog; polls vote; etc.). No edit, reply, forward,
 * share, inline reactions, or per-emoji reactor breakdown.
 */
data class ActionPolicy(
    val allowEdit: Boolean,
    val allowReply: Boolean,
    val allowForward: Boolean,
    val allowShare: Boolean,
    /** Long-press emoji quick-strip + the right-of-bubble "AddReaction" hover icon. */
    val allowInlineReactions: Boolean,
    /** "Show all reactions" / per-emoji reactor breakdown sheet. */
    val allowReactionDetails: Boolean,
) {
    companion object {
        val Standard = ActionPolicy(
            allowEdit = true,
            allowReply = true,
            allowForward = true,
            allowShare = true,
            allowInlineReactions = true,
            allowReactionDetails = true,
        )

        val StructuredOneShot = ActionPolicy(
            allowEdit = false,
            allowReply = false,
            allowForward = false,
            allowShare = false,
            allowInlineReactions = false,
            allowReactionDetails = false,
        )
    }
}
