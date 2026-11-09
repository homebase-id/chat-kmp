package id.homebase.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class NotificationActionBridgeTest {
    private val calls = mutableListOf<String>()

    private fun TestScope.bridge(failOn: String? = null) = NotificationActionBridge(
        sendMessage = { _, _ -> },
        markAllAsRead = { calls += "read:$it"; if (failOn == "read") error("boom") },
        flushLastRead = { calls += "flush" },
        clearNotifications = { calls += "clear:$it" },
        scope = this,
    )

    @Test
    fun markAsReadMarksFlushesThenClears() = runTest {
        val id = Uuid.random()
        var result: Boolean? = null
        bridge().markAsRead(id.toString()) { result = it }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("read:$id", "flush", "clear:$id"), calls)
        assertEquals(true, result)
    }

    @Test
    fun failureReportsFalseAndKeepsTheNotification() = runTest {
        val id = Uuid.random()
        var result: Boolean? = null
        bridge(failOn = "read").markAsRead(id.toString()) { result = it }
        testScheduler.advanceUntilIdle()
        assertEquals(false, result)
        assertEquals(listOf("read:$id"), calls)
    }
}
