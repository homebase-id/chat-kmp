package id.homebase.chat.services.calling

import id.homebase.core.notifications.RichNotificationData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopCallRingSinkTest {
    @Test
    fun ringingPostsANotificationRaisesTheWindowAndRepeatsTheToneUntilStopped() = runTest {
        val notifications = mutableListOf<RichNotificationData>()
        val cancelled = mutableListOf<Int>()
        var raised = 0
        var tones = 0
        val sink = DesktopCallRingSink(
            scope = backgroundScope,
            notify = { notifications += it },
            cancelNotification = { cancelled += it },
            bringToFront = { raised++ },
            ringTone = { tones++ },
            incomingCallText = { "Incoming call" },
            ringIntervalMs = 1_000,
        )

        sink.ring("c1", "sam.demo.rocks")
        runCurrent()
        advanceTimeBy(2_500)
        assertEquals(3, tones)
        assertEquals(1, raised)
        assertEquals(listOf("sam.demo.rocks" to "Incoming call"), notifications.map { it.title to it.body })

        sink.ring("c1", "Samwise")
        runCurrent()
        assertEquals(1, raised)
        assertEquals("Samwise", notifications.last().title)
        assertEquals(listOf(notifications.first().notificationId), cancelled)

        sink.stopRinging("c1")
        advanceTimeBy(5_000)
        assertEquals(3, tones)
        assertEquals(2, cancelled.size)
    }
}
