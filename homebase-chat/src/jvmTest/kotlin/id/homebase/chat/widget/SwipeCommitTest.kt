package id.homebase.chat.widget

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The release decision behind the conversation-list swipe. Distance is the primary path;
 * the flick shortcut is deliberately hard to hit so an accidental swipe can't archive a
 * conversation (Signal tunes theirs the same way).
 */
class SwipeCommitTest {

    private val threshold = 120f
    private val escape = 1000f

    @Test
    fun pastThresholdCommits() {
        assertTrue(shouldCommitSwipe(threshold, threshold, 0f, escape))
        assertTrue(shouldCommitSwipe(-threshold - 1f, threshold, 0f, escape))
    }

    @Test
    fun shortSlowDragSpringsBackWithoutCommitting() {
        assertFalse(shouldCommitSwipe(30f, threshold, 50f, escape))
    }

    @Test
    fun fastFlickPastHalfwayCommitsShortOfTheThreshold() {
        assertTrue(shouldCommitSwipe(70f, threshold, 1500f, escape))
        assertTrue(shouldCommitSwipe(-70f, threshold, -1500f, escape))
    }

    @Test
    fun fastFlickThatBarelyMovedDoesNotCommit() {
        assertFalse(shouldCommitSwipe(20f, threshold, 4000f, escape))
    }

    @Test
    fun flingBackTowardsRestDoesNotCommit() {
        // Dragged right, then flicked back left on release — the user changed their mind.
        assertFalse(shouldCommitSwipe(70f, threshold, -2000f, escape))
    }

    @Test
    fun withoutAnEscapeVelocityOnlyDistanceCommits() {
        assertFalse(shouldCommitSwipe(70f, threshold, 9000f, escapeVelocityPxPerSecond = null))
        assertTrue(shouldCommitSwipe(130f, threshold, 0f, escapeVelocityPxPerSecond = null))
    }

    @Test
    fun anUnmeasuredRowNeverCommits() {
        // A fraction-of-width threshold is 0 until the row has been measured; treating that
        // as "everything is past the threshold" would archive on the first stray pixel.
        assertFalse(shouldCommitSwipe(500f, thresholdPx = 0f, velocityPxPerSecond = 0f, escapeVelocityPxPerSecond = escape))
    }

    @Test
    fun aStationaryPointerNeverCommits() {
        assertFalse(shouldCommitSwipe(0f, threshold, 5000f, escape))
    }
}
