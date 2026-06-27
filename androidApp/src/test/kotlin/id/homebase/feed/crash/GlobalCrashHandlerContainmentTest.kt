package id.homebase.feed.crash

import id.homebase.api.client.NetworkException
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Guards [GlobalCrashHandler.isContainableNonFatal] — the decision that keeps a transient
 * network failure (incl. a TLS-inspecting VPN/proxy throwing SSLHandshakeException on every
 * call) from killing an already-authenticated app, while a genuine bug still terminates.
 *
 * The process-kill itself can't be unit-tested (it would kill the test JVM), so this pins the
 * pure decision that gates it. See TlsInterceptionTest for how the failure reaches the handler.
 */
class GlobalCrashHandlerContainmentTest {

    private val trustAnchorError = SSLHandshakeException(
        "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found."
    )

    @Test
    fun tlsHandshakeFailure_isContained() {
        // A VPN/proxy/AV intercepting HTTPS → handshake fails → must NOT crash the app.
        assertTrue(GlobalCrashHandler.isContainableNonFatal(trustAnchorError))
    }

    @Test
    fun networkExceptionWrappingTls_isContained() {
        // As surfaced by the API layer (OdinApiProviderBase wraps transport failures).
        assertTrue(GlobalCrashHandler.isContainableNonFatal(NetworkException(trustAnchorError)))
    }

    @Test
    fun plainNetworkIoFailure_isContained() {
        assertTrue(GlobalCrashHandler.isContainableNonFatal(IOException("socket closed")))
    }

    @Test
    fun genuineBug_isNotContained_soItStillCrashes() {
        // The note-to-self bootstrap bug, a NPE, etc. must still terminate + report.
        assertFalse(GlobalCrashHandler.isContainableNonFatal(IllegalStateException("No active credentials set")))
        assertFalse(GlobalCrashHandler.isContainableNonFatal(NullPointerException()))
        assertFalse(GlobalCrashHandler.isContainableNonFatal(IllegalArgumentException("bad")))
    }
}
