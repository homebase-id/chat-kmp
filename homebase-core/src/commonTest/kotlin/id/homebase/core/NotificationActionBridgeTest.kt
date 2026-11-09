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

    private inner class FakeActions(private val failOn: String?) : NotificationActions {
        override suspend fun sendMessage(conversationId: Uuid, text: String) {}
        override suspend fun markAllAsRead(conversationId: Uuid) {
            calls += "read:$conversationId"
            if (failOn == "read") error("boom")
        }
        override suspend fun flushLastRead() {
            calls += "flush"
        }
        override fun clearNotifications(conversationId: String) {
            calls += "clear:$conversationId"
        }
    }

    private fun TestScope.bridge(failOn: String? = null) = NotificationActionBridge(FakeActions(failOn), this)

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
