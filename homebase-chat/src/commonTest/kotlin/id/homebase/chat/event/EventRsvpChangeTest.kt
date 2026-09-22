package id.homebase.chat.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EventRsvpChangeTest {

    @Test
    fun tapping_new_rsvp_sets_it_and_clears_the_other_two() {
        val change = EventRsvp.change(currentRsvp = null, tapped = EventRsvp.GOING)
        assertEquals(setOf(EventRsvp.GOING), change.add)
        assertEquals(setOf(EventRsvp.MAYBE, EventRsvp.NOT_GOING), change.remove)
    }

    @Test
    fun switching_rsvp_clears_every_other_choice_not_just_the_known_one() {
        val change = EventRsvp.change(currentRsvp = EventRsvp.MAYBE, tapped = EventRsvp.NOT_GOING)
        assertEquals(setOf(EventRsvp.NOT_GOING), change.add)
        assertEquals(setOf(EventRsvp.GOING, EventRsvp.MAYBE), change.remove)
    }

    @Test
    fun tapping_current_rsvp_retracts_all_three() {
        val change = EventRsvp.change(currentRsvp = EventRsvp.GOING, tapped = EventRsvp.GOING)
        assertEquals(emptySet(), change.add)
        assertEquals(EventRsvp.ALL, change.remove)
    }

    @Test
    fun scope_is_the_same_for_every_tap_so_a_newer_tap_replaces_a_pending_one() {
        val a = EventRsvp.change(null, EventRsvp.GOING)
        val b = EventRsvp.change(EventRsvp.GOING, EventRsvp.MAYBE)
        assertEquals(a.scope, b.scope)
    }

    @Test
    fun rejects_non_rsvp_emoji() {
        assertFailsWith<IllegalArgumentException> { EventRsvp.change(null, "👍") }
    }
}
