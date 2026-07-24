package id.homebase.chat.services

import id.homebase.chat.event.EventDescriptor
import id.homebase.chat.groodle.GroodleDescriptor
import id.homebase.chat.groodle.GroodleSlot
import id.homebase.chat.poll.PollDescriptor
import id.homebase.chat.services.builder.LocationPreviewDescriptor
import id.homebase.chat.services.content.MessageContent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the #887 auto-pin decision ([shouldAutoPin]). The core invariant: only
 * still-actionable typed messages auto-pin, so a re-delivery via BatchReceived
 * (a peer's reaction, an edit, or a fresh device's first sighting) can't resurrect
 * a pin the user already dismissed by voting or by the event/share ending — the
 * per-device in-memory guard can't carry that across devices/restarts.
 */
class AutoPinDecisionTest {

    private val now = 1_000_000L

    private fun poll(vararg options: String) =
        MessageContent.Poll(PollDescriptor(question = "Q?", options = options.toList()))

    private fun groodle() = MessageContent.Groodle(
        GroodleDescriptor(
            title = "When?",
            timezone = "Europe/Copenhagen",
            slots = listOf(GroodleSlot(startUtcMs = now)),
        ),
    )

    private fun event(endUtcMs: Long?, startUtcMs: Long = now) = MessageContent.Event(
        EventDescriptor(
            title = "Party",
            startUtcMs = startUtcMs,
            endUtcMs = endUtcMs,
            timezone = "Europe/Copenhagen",
        ),
    )

    private fun location(liveShareUntilMs: Long?) = MessageContent.Location(
        LocationPreviewDescriptor(
            lat = 0.0, lon = 0.0, address = "somewhere",
            hasImage = false, imageWidth = null, imageHeight = null,
            liveShareUntilMs = liveShareUntilMs,
        ),
    )

    // --- Poll: pin only until answered ------------------------------------

    @Test fun poll_unanswered_pins() =
        assertTrue(shouldAutoPin(poll("a", "b"), ownReactions = emptyList(), nowMs = now))

    @Test fun poll_answered_doesNotPin() =
        assertFalse(shouldAutoPin(poll("a", "b"), ownReactions = listOf("_p0"), nowMs = now))

    @Test fun poll_nullDescriptor_doesNotPin() =
        assertFalse(shouldAutoPin(MessageContent.Poll(null), emptyList(), now))

    // --- Groodle: pin only until answered ---------------------------------

    @Test fun groodle_unanswered_pins() =
        assertTrue(shouldAutoPin(groodle(), ownReactions = emptyList(), nowMs = now))

    @Test fun groodle_answered_doesNotPin() =
        assertFalse(shouldAutoPin(groodle(), ownReactions = listOf("_1Y"), nowMs = now))

    @Test fun groodle_nullDescriptor_doesNotPin() =
        assertFalse(shouldAutoPin(MessageContent.Groodle(null), emptyList(), now))

    // --- Event: pin only until it ends ------------------------------------

    @Test fun event_future_pins() =
        assertTrue(shouldAutoPin(event(endUtcMs = now + 1000), emptyList(), now))

    @Test fun event_ended_doesNotPin() =
        assertFalse(shouldAutoPin(event(endUtcMs = now - 1000), emptyList(), now))

    @Test fun event_openEnded_withinOneHour_pins() =
        assertTrue(shouldAutoPin(event(endUtcMs = null, startUtcMs = now - 1000), emptyList(), now))

    @Test fun event_openEnded_pastOneHour_doesNotPin() =
        assertFalse(
            shouldAutoPin(event(endUtcMs = null, startUtcMs = now - 3_600_001L), emptyList(), now),
        )

    @Test fun event_nullDescriptor_doesNotPin() =
        assertFalse(shouldAutoPin(MessageContent.Event(null), emptyList(), now))

    // --- Location: pin only while the live share is active ----------------

    @Test fun location_liveShareActive_pins() =
        assertTrue(shouldAutoPin(location(liveShareUntilMs = now + 1000), emptyList(), now))

    @Test fun location_liveShareEnded_doesNotPin() =
        assertFalse(shouldAutoPin(location(liveShareUntilMs = now - 1000), emptyList(), now))

    @Test fun location_static_doesNotPin() =
        assertFalse(shouldAutoPin(location(liveShareUntilMs = null), emptyList(), now))

    // --- Plain messages never auto-pin ------------------------------------

    @Test fun nullContent_doesNotPin() =
        assertFalse(shouldAutoPin(content = null, ownReactions = emptyList(), nowMs = now))
}
