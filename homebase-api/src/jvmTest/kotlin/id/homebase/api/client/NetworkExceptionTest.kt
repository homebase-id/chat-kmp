package id.homebase.api.client

import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [NetworkException] and the [isTransientNetworkFailure] predicate the
 * platform uncaught-exception handlers use to keep a dropped connection from
 * killing the process. The predicate must be precise: transient transport
 * failures classify true (recorded as non-fatals), every other failure classifies
 * false (still fatal).
 */
class NetworkExceptionTest {

    @Test
    fun `NetworkException preserves cause and reports no-http status`() {
        val cause = IOException("connect timeout")
        val ex = NetworkException(cause)
        assertSame(cause, ex.cause)
        assertEquals(0, ex.status, "status 0 signals no HTTP response")
    }

    @Test
    fun `NetworkException classifies as transient`() {
        assertTrue(NetworkException(IOException("boom")).isTransientNetworkFailure())
    }

    @Test
    fun `raw IOException classifies as transient`() {
        // Covers network paths that bypass the API layer (e.g. the WebSocket),
        // which throw a raw IOException rather than a wrapped NetworkException.
        assertTrue(IOException("socket closed").isTransientNetworkFailure())
    }

    @Test
    fun `NetworkException nested in a cause chain classifies as transient`() {
        val wrapped = RuntimeException("thumb load failed", NetworkException(IOException("dns")))
        assertTrue(wrapped.isTransientNetworkFailure())
    }

    @Test
    fun `non-network failures classify as fatal`() {
        assertFalse(IllegalStateException("bug").isTransientNetworkFailure())
        assertFalse(NullPointerException().isTransientNetworkFailure())
        // An HTTP-status API error means the server DID respond — not a transport failure.
        assertFalse(NotFoundException().isTransientNetworkFailure())
        assertFalse(UnauthorizedException().isTransientNetworkFailure())
    }

    @Test
    fun `a cyclic cause chain does not loop forever`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b) // a -> b -> a cycle
        // Must terminate (guarded by a visited set) and, with no network type
        // present, classify as fatal.
        assertFalse(a.isTransientNetworkFailure())
    }
}
