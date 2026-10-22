package id.homebase.api.client.websockets

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * #1921: a connect attempt that never reaches the 101 (CIO waits on TLS/upgrade with no timeout)
 * must fail, so the reconnect loop iterates instead of hanging forever.
 */
class WebSocketUpgradeDeadlineTest {

    @Test
    fun hungUpgradeFailsAtTheDeadline() = runTest {
        assertFailsWith<WebSocketUpgradeTimeoutException> {
            withWebSocketUpgradeDeadline(timeoutMs = 1_000L) { awaitCancellation() }
        }
        assertEquals(1_000L, currentTime)
    }

    @Test
    fun sessionOutlivesTheDeadlineOnceUpgraded() = runTest {
        val result = withWebSocketUpgradeDeadline(timeoutMs = 1_000L) { upgraded ->
            upgraded()
            delay(10_000L)
            "session ended"
        }
        assertEquals("session ended", result)
    }

    @Test
    fun connectErrorPropagatesUnchanged() = runTest {
        assertFailsWith<IllegalStateException> {
            withWebSocketUpgradeDeadline(timeoutMs = 1_000L) { error("upgrade rejected") }
        }
    }
}
