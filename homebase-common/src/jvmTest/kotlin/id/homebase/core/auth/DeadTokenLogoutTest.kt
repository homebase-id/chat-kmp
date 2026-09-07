package id.homebase.core.auth

import id.homebase.api.client.websockets.isWebSocketUpgradeUnauthorized
import io.ktor.client.plugins.websocket.WebSocketException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The exact message Ktor 3.4.3 builds at WebSockets.kt:233, captured from a real revoked
// registration on a physical Android device (#1349). If a Ktor upgrade rewords this, these tests
// fail — which is the point: the dead-token logout is driven entirely off this string.
private const val REAL_UPGRADE_401 =
    "Handshake exception, expected status code 101 but was 401"

class DeadTokenLogoutTest {

    @Test
    fun `detects the real Ktor upgrade 401`() {
        assertTrue(WebSocketException(REAL_UPGRADE_401).isWebSocketUpgradeUnauthorized())
    }

    @Test
    fun `does not match the 101 in the same message`() {
        assertFalse(
            WebSocketException("Handshake exception, expected status code 101 but was 403")
                .isWebSocketUpgradeUnauthorized()
        )
    }

    @Test
    fun `does not match transient transport failures`() {
        assertFalse(IOException("Connection reset").isWebSocketUpgradeUnauthorized())
        assertFalse(IOException("Software caused connection abort").isWebSocketUpgradeUnauthorized())
        assertFalse(WebSocketException("Handshake exception, expected status code 101 but was 502")
            .isWebSocketUpgradeUnauthorized())
    }

    @Test
    fun `unwraps a wrapped cause`() {
        val wrapped = IllegalStateException("connect failed", WebSocketException(REAL_UPGRADE_401))
        assertTrue(wrapped.isWebSocketUpgradeUnauthorized())
    }

    // The cause walk mirrors isTransientNetworkFailure's guarded shape. Without the visited set
    // this test hangs rather than fails.
    @Test
    fun `survives a cyclic cause chain`() {
        assertFalse(CyclicThrowable().isWebSocketUpgradeUnauthorized())
    }

    @Test
    fun `consecutive upgrade 401s advance the count to the threshold`() {
        var count = 0
        repeat(DEAD_TOKEN_THRESHOLD) {
            count = nextUpgradeAuthFailureCount(count, WebSocketException(REAL_UPGRADE_401))
        }
        assertEquals(DEAD_TOKEN_THRESHOLD, count)
    }

    // The acceptance criterion: a single transient 401 must not nuke a valid session.
    @Test
    fun `a one-off 401 followed by a transport failure never reaches the threshold`() {
        var count = 0
        repeat(10) {
            count = nextUpgradeAuthFailureCount(count, WebSocketException(REAL_UPGRADE_401))
            count = nextUpgradeAuthFailureCount(count, IOException("Connection reset"))
            assertTrue(count < DEAD_TOKEN_THRESHOLD, "transient churn must not log out")
        }
    }

    @Test
    fun `a non-401 failure resets a partial streak`() {
        var count = nextUpgradeAuthFailureCount(0, WebSocketException(REAL_UPGRADE_401))
        count = nextUpgradeAuthFailureCount(count, WebSocketException(REAL_UPGRADE_401))
        assertEquals(2, count)
        assertEquals(0, nextUpgradeAuthFailureCount(count, IOException("Connection reset")))
    }
}

private class CyclicThrowable : RuntimeException("boom") {
    override val cause: Throwable get() = this
}
