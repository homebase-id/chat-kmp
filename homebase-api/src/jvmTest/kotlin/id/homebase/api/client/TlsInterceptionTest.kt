package id.homebase.api.client

import id.homebase.api.client.auth.CredentialsManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The two HTTP-layer contracts the crash-containment fix relies on
 * ([GlobalCrashHandler.isContainableNonFatal], pinned by `GlobalCrashHandlerContainmentTest`),
 * exercised against a TLS-intercepting network — a VPN/proxy/antivirus doing "TLS inspection"
 * that makes every HTTPS call fail with the exact error a user in the wild hit:
 *
 *   SSLHandshakeException: java.security.cert.CertPathValidatorException:
 *   Trust anchor for certification path not found.
 *
 *  1. `request()` maps that transport/TLS failure to a typed [NetworkException], preserving the
 *     raw SSL cause — so callers (and the login "Show error details" toggle) can read it.
 *  2. The result classifies as [isTransientNetworkFailure] == true — the signal the global
 *     handler keys on to *contain* it instead of crashing the app.
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
    fun tlsFailure_isClassifiedTransient() {
        // Raw, and wrapped exactly as the API layer wraps it — both must be "transient" so the
        // global handler contains it (isContainableNonFatal) instead of terminating the process.
        assertTrue(trustAnchorError.isTransientNetworkFailure())
        assertTrue(NetworkException(trustAnchorError).isTransientNetworkFailure())
    }
}
