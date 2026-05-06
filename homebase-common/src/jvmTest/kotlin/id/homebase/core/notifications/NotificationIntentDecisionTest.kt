package id.homebase.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Locks down the decision wiring at MainActivity.handleNotificationIntent — what
 * the bug-from-the-log actually exercised. The bit-mask helper is already pinned
 * by [IsReplayedFromHistoryTest]; this test pins the call site that combines
 * the flag check, the marker-extra check, and the hand-off to NotificationService.
 *
 * Replicates 2026-05-01 08:05:49: a launching Intent with the notification
 * marker still set AND `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` because the user
 * resumed the app from launcher. Decision must be Skip — otherwise the user
 * gets auto-navigated to a 73-minute-old notification's conversation.
 */
class NotificationIntentDecisionTest {

    private val FLAG_NEW_TASK = 0x10000000           // Intent.FLAG_ACTIVITY_NEW_TASK
    private val FLAG_HISTORY = FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY

    private val examplePayload: Map<String, Any> = mapOf(
        "data" to """{"senderId":"x","timestamp":1,"options":{"appId":"a","typeId":"b","tagId":"c"}}""",
        "notification_tap" to true,
        "notification_conversation_id" to "42af4ac1-cc54-4a81-aa02-2a7854ce0980",
    )

    @Test
    fun replayedIntentWithMarker_isSkipped() {
        val decision = decideNotificationIntent(
            flags = FLAG_HISTORY or FLAG_NEW_TASK,
            isMarkedAsTap = true,
            payload = examplePayload,
        )
        assertEquals(NotificationIntentDecision.Skip, decision)
    }

    @Test
    fun freshIntentWithMarker_isProcessed() {
        val decision = decideNotificationIntent(
            flags = FLAG_NEW_TASK,
            isMarkedAsTap = true,
            payload = examplePayload,
        )
        assertTrue(decision is NotificationIntentDecision.Process)
    }

    @Test
    fun intentWithoutMarker_isSkipped() {
        // Deep-links and other intents that lack the EXTRA_NOTIFICATION_TAP marker
        // must not be routed through NotificationService.handleNotificationClicked.
        val decision = decideNotificationIntent(
            flags = 0,
            isMarkedAsTap = false,
            payload = emptyMap<String, Any>(),
        )
        assertEquals(NotificationIntentDecision.Skip, decision)
    }

    @Test
    fun replayedIntentWithoutMarker_isSkipped() {
        // Both conditions are independently sufficient for Skip.
        val decision = decideNotificationIntent(
            flags = FLAG_HISTORY,
            isMarkedAsTap = false,
            payload = emptyMap<String, Any>(),
        )
        assertEquals(NotificationIntentDecision.Skip, decision)
    }

    @Test
    fun processBranchPreservesPayload() {
        val decision = decideNotificationIntent(
            flags = FLAG_NEW_TASK,
            isMarkedAsTap = true,
            payload = examplePayload,
        )
        val process = decision as NotificationIntentDecision.Process
        // Must hand the exact same map through — no copy, no transformation.
        assertSame(examplePayload, process.payload)
    }
}
