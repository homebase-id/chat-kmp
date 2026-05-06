package id.homebase.chat.services.content

import id.homebase.chat.event.EventDescriptor

/**
 * Typed rich-content riding on the message header (parsed from `dataType` +
 * `appData.content`). Distinct from regular text/media messages, which keep
 * their existing path through [id.homebase.chat.services.MessageAppData].
 *
 * Adding a new kind (poll, doodle, dice):
 *   1. Add a sealed subtype here. The default [actions] policy is
 *      [ActionPolicy.StructuredOneShot] — no edit/reply/forward/inline
 *      reactions — which is the right starting point for every rich-content
 *      kind we have planned. Override only if a kind needs different rules.
 *   2. Add the dataType constant in `ChatProtocol`.
 *   3. Add a `when` branch in [MessageContentParser.parse].
 *   4. Add a `when` branch in `MessageBubbleRaw` to render the bubble.
 *   5. Add a sender entry on `ChatMessageSenderService` mirroring `sendNewEventMessage`.
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

    /** A scheduled event with optional location, meeting URL, and RSVP via reactions. */
    data class Event(val descriptor: EventDescriptor) : MessageContent {
        override val displayLabel: String get() = descriptor.title
    }

    // Future:
    // data class Poll(val descriptor: PollDescriptor) : MessageContent
    // data class Doodle(val descriptor: DoodleDescriptor) : MessageContent
    // data class Dice(val descriptor: DiceDescriptor) : MessageContent
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
