package id.homebase.chat.calling

import kotlin.test.Test
import kotlin.test.assertEquals

class CallEventVisualTest {
    private fun visual(outcome: CallOutcome, own: Boolean) = callEventVisual(CallEventDescriptor(outcome), own)

    @Test
    fun peerNoAnswerAndCancelledAreMissed() {
        listOf(CallOutcome.NoAnswer, CallOutcome.Cancelled).forEach {
            val v = visual(it, own = false)
            assertEquals(true, v.missed)
            assertEquals(CallEventIcon.Missed, v.icon)
            assertEquals(CallEventTitle.Missed, v.title)
            assertEquals(CallLogDirection.Incoming, v.direction)
        }
    }

    @Test
    fun ownNoAnswerAndCancelledAreNeverMissed() {
        assertEquals(CallEventVisual(CallLogDirection.Outgoing, false, CallEventIcon.NoAnswerOutgoing, CallEventTitle.NoAnswer), visual(CallOutcome.NoAnswer, true))
        assertEquals(CallEventVisual(CallLogDirection.Outgoing, false, CallEventIcon.Cancelled, CallEventTitle.Cancelled), visual(CallOutcome.Cancelled, true))
    }

    @Test
    fun completedFollowsDirection() {
        assertEquals(CallEventVisual(CallLogDirection.Outgoing, false, CallEventIcon.Outgoing, CallEventTitle.Outgoing), visual(CallOutcome.Completed, true))
        assertEquals(CallEventVisual(CallLogDirection.Incoming, false, CallEventIcon.Incoming, CallEventTitle.Incoming), visual(CallOutcome.Completed, false))
    }

    @Test
    fun declinedBusyFailedIgnoreDirectionForIconAndTitle() {
        listOf(true, false).forEach { own ->
            assertEquals(CallEventIcon.Declined to CallEventTitle.Declined, visual(CallOutcome.Declined, own).let { it.icon to it.title })
            assertEquals(CallEventIcon.Busy to CallEventTitle.Busy, visual(CallOutcome.Busy, own).let { it.icon to it.title })
            assertEquals(CallEventIcon.Failed to CallEventTitle.Failed, visual(CallOutcome.Failed, own).let { it.icon to it.title })
            assertEquals(false, visual(CallOutcome.Declined, own).missed)
        }
    }
}
