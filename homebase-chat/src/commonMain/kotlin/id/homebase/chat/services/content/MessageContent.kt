package id.homebase.chat.services.content

import id.homebase.chat.event.EventDescriptor

/**
 * Typed rich-content riding on the message header (parsed from `dataType` +
 * `appData.content`). Distinct from regular text/media messages, which keep
 * their existing path through [id.homebase.chat.services.MessageAppData].
 *
 * Adding a new kind (poll, doodle):
 *   1. Add a sealed subtype here.
 *   2. Add the dataType constant in `ChatProtocol`.
 *   3. Add a `when` branch in [MessageContentParser.parse].
 *   4. Add a `when` branch in `MessageBubbleRaw` to render the bubble.
 *   5. Add a sender entry on `ChatMessageSenderService` mirroring `sendNewEventMessage`.
 */
sealed interface MessageContent {

    /** A scheduled event with optional location, meeting URL, and RSVP via reactions. */
    data class Event(val descriptor: EventDescriptor) : MessageContent

    // Future:
    // data class Poll(val descriptor: PollDescriptor) : MessageContent
    // data class Doodle(val descriptor: DoodleDescriptor) : MessageContent
}
