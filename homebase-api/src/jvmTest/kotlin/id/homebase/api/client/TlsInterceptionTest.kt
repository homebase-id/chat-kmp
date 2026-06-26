package id.homebase.api.client

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.coroutines.supervisedScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Simulates the field situation: a TLS-intercepting network — a VPN/proxy/antivirus doing
 * "TLS inspection", or any MITM presenting a certificate from a CA the device doesn't trust
 * — makes every HTTPS call fail with the exact error a user in the wild hit:
 *
 *   SSLHandshakeException: java.security.cert.CertPathValidatorException:
 *   Trust anchor for certification path not found.
 *
 * What these tests pin:
 *  1. The HTTP layer maps that SSL failure to a typed [NetworkException] (catchable), with
 *     the raw SSL cause preserved — so well-behaved callers can degrade gracefully.
 *  2. It classifies as [isTransientNetworkFailure] == true, i.e. the Android global handler
 *     treats it as the "quiet" branch: no table-flip recovery screen, recorded only as a
 *     non-fatal that often loses the flush race. That's the mechanism behind the original
 *     "crashes, no screen, nothing in Crashlytics" report.
 *  3. On a *supervised* scope (the app's protected scopes) the failure is contained — a
 *     sibling survives and the scope stays alive.
 *  4. On a bare scope with no [CoroutineExceptionHandler] (the shape of `viewModelScope`),
 *     it escapes uncaught — the robustness gap that turns an intercepting VPN into a
 *     repeating, silent crash for an already-authenticated user.
 */
class TlsInterceptionTest {

    private val trustAnchorError
        get() = SSLHandshakeException(
            "java.security.cert.CertPathValidatorException: " +
                "Trust anchor for certification path not found."
        )

    /** Minimal concrete provider exposing the protected request() primitive. */
    private class TestProvider(client: HttpClient) :
        OdinApiProviderBase(client, CredentialsManager()) {
        suspend fun ping(): ApiResponse = request(
            block = { httpClient.get("https://queralt.dominion.id/api/v2/health/ping") },
            secret = null,
        )
    }

    /** A client whose every call dies in the TLS handshake, as a MITM/intercepting VPN does. */
    private fun interceptingClient(): HttpClient =
        HttpClient(MockEngine { throw trustAnchorError })

    @Test
    fun apiCall_throughTlsInterception_mapsToTypedNetworkException() = runTest {
        val provider = TestProvider(interceptingClient())

        val thrown = assertFailsWith<NetworkException> { provider.ping() }

        // The raw SSL cause is preserved for the crash report / the login "details" toggle.
        val cause = thrown.cause
        assertIs<SSLHandshakeException>(cause)
        assertTrue(
            cause.message?.contains("Trust anchor") == true,
            "the SSL cause must be carried; was: ${cause.message}",
        )
    }

    @Test
    fun tlsFailure_isClassifiedTransient_soItTakesTheSilentBranch() {
        // Raw, and wrapped exactly as the API layer wraps it — both must be "transient" so
        // the global handler skips the recovery screen and records only a (often-unflushed)
        // non-fatal. This is why such a crash is silent and absent from Crashlytics.
        assertTrue(trustAnchorError.isTransientNetworkFailure())
        assertTrue(NetworkException(trustAnchorError).isTransientNetworkFailure())
    }

    @Test
    fun tlsFailure_onSupervisedScope_isContained() = runTest {
        val scope = supervisedScope("drive-sync", StandardTestDispatcher(testScheduler))

        var siblingCompleted = false
        scope.launch { throw NetworkException(trustAnchorError) }
        scope.launch { siblingCompleted = true }
        advanceUntilIdle()

        assertTrue(siblingCompleted, "a sibling must survive the TLS failure")
        assertTrue(scope.isActive, "the supervised scope must stay alive")
    }

    @Test
    fun tlsFailure_onBareScope_escapesUncaught_theCrash() = runTest {
        // A viewModelScope-shaped scope: SupervisorJob, no CoroutineExceptionHandler. The
        // handler here stands in for the uncaught path (the real viewModelScope has none, so
        // on Android the escape reaches Thread.setDefaultUncaughtExceptionHandler). Unconfined
        // runs the body inline so the throw propagates synchronously.
        var escaped: Throwable? = null
        val viewModelLikeScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined +
                CoroutineExceptionHandler { _, e -> escaped = e },
        )

        // e.g. a sync/API call launched without a local try/catch.
        val job = viewModelLikeScope.launch { throw NetworkException(trustAnchorError) }
        job.join()

        assertNotNull(
            escaped,
            "the TLS failure escaped the launch uncaught — on Android this reaches the global handler",
        )
        assertIs<NetworkException>(escaped)
    }
}
