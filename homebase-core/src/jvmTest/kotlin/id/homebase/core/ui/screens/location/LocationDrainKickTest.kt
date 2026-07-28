package id.homebase.core.ui.screens.location

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the forced-drain kick policy after a flush (#987/#1018): kick when any hour was
 * handed to the outbox, AND when any hour was refused — a refusal means a pending row is
 * already in the outbox blocking that hour, and the forced drain is the only thing that
 * sends it on a pushless background wake (iOS SLC relaunch, Android PendingIntent cold
 * start). Gating on Enqueued alone reproduced the #1018 strand: "refusing to replace a
 * pending UploadNewFile" every few minutes with no drain until the next foreground.
 */
class LocationDrainKickTest {

    @Test
    fun enqueuedKicksDrain() {
        assertTrue(shouldKickDrain(listOf(HourFlushOutcome.Enqueued)))
    }

    @Test
    fun refusedKicksDrain() {
        // The #1018 pileup: every hour refused because a stranded un-sent create blocks
        // them — the drain must fire so the blocking row itself gets sent.
        assertTrue(shouldKickDrain(listOf(HourFlushOutcome.Refused)))
        assertTrue(shouldKickDrain(listOf(HourFlushOutcome.Refused, HourFlushOutcome.Refused)))
    }

    @Test
    fun noRowsAloneDoesNotKick() {
        assertFalse(shouldKickDrain(listOf(HourFlushOutcome.NoRows)))
        assertFalse(shouldKickDrain(listOf(HourFlushOutcome.NoRows, HourFlushOutcome.NoRows)))
    }

    @Test
    fun nothingFlushedDoesNotKick() {
        assertFalse(shouldKickDrain(emptyList()))
    }

    @Test
    fun mixedOutcomesKickWheneverAnyHourNeedsSending() {
        assertTrue(
            shouldKickDrain(
                listOf(HourFlushOutcome.NoRows, HourFlushOutcome.Refused, HourFlushOutcome.NoRows)
            )
        )
        assertTrue(
            shouldKickDrain(
                listOf(HourFlushOutcome.NoRows, HourFlushOutcome.Enqueued)
            )
        )
    }
}
