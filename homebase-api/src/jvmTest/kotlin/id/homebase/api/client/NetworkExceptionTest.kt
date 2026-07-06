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
    fun `UnknownHostException (OkHttp-Android shape) classifies as transient`() {
        // Regression guard for Android: OkHttp throws UnknownHostException, an IOException,
        // on DNS failure — must stay classified after broadening for the CIO shape.
        assertTrue(java.net.UnknownHostException("no host").isTransientNetworkFailure())
    }

    @Test
    fun `UnresolvedAddressException (Ktor CIO-Desktop shape) classifies as transient`() {
        // Desktop variant: Ktor CIO throws UnresolvedAddressException — an
        // IllegalArgumentException, NOT an IOException — on DNS failure / offline. It must
        // classify as a network error so the platform uncaught handlers swallow it, exactly
        // like the OkHttp UnknownHostException case above.
        assertTrue(java.nio.channels.UnresolvedAddressException().isTransientNetworkFailure())
    }

    @Test
    fun `UnresolvedAddressException nested in a cause chain classifies as transient`() {
        val wrapped = RuntimeException("security context fetch failed",
            java.nio.channels.UnresolvedAddressException())
        assertTrue(wrapped.isTransientNetworkFailure())
    }

    @Test
    fun `a plain IllegalArgumentException stays fatal`() {
        // The CIO match is narrow (the specific class name), NOT its IllegalArgumentException
        // supertype — a generic IllegalArgumentException is a programming bug, not a transport
        // failure, and must remain fatal.
        assertFalse(IllegalArgumentException("bad arg").isTransientNetworkFailure())
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
