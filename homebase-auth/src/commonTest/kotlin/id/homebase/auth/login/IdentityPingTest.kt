package id.homebase.auth.login

import id.homebase.api.common.OdinId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The login pre-check must tell "couldn't reach you" apart from "that isn't a Homebase
 * identity" — the old code reported every timeout/offline/non-200 as the latter.
 */
class IdentityPingTest {

    private val identity = OdinId("sam.homebase.id")

    private fun clientReturning(status: HttpStatusCode) = HttpClient(
        MockEngine { respond(content = "", status = status) }
    ) { install(HttpTimeout) }

    private fun clientThrowing() = HttpClient(
        MockEngine { throw RuntimeException("simulated network failure") }
    ) { install(HttpTimeout) }

    /**
     * Simulates a TLS-intercepting network (VPN/ad-blocker/AV). commonTest can't reference
     * `javax.net.ssl.SSLHandshakeException`, so we throw the real-world message — the
     * classifier matches on class name + message, and on JVM/Android the real type's name
     * ("SSLHandshakeException") matches too.
     */
    private fun clientThrowingTls() = HttpClient(
        MockEngine {
            throw RuntimeException(
                "javax.net.ssl.SSLHandshakeException: java.security.cert.CertPathValidatorException: " +
                    "Trust anchor for certification path not found."
            )
        }
    ) { install(HttpTimeout) }

    @Test
    fun http200_isOk() = runTest {
        assertEquals(
            IdentityPingResult.Ok,
            pingIdentity(clientReturning(HttpStatusCode.OK), identity),
        )
    }

    @Test
    fun http404_isNotHomebase_withStatus() = runTest {
        // Reached a server, but it isn't answering as a Homebase identity.
        val result = pingIdentity(clientReturning(HttpStatusCode.NotFound), identity)
        assertIs<IdentityPingResult.NotHomebase>(result)
        assertEquals(404, result.statusCode)
    }

    @Test
    fun http503_isUnreachable_serverError() = runTest {
        // A 5xx means we reached the host but it's erroring — "try again", not "wrong ID".
        val result = pingIdentity(clientReturning(HttpStatusCode.ServiceUnavailable), identity)
        assertIs<IdentityPingResult.Unreachable>(result)
        assertTrue(result.detail.contains("503"), "detail was: ${result.detail}")
    }

    @Test
    fun http500_isUnreachable() = runTest {
        // Lower boundary of the 5xx range.
        val result = pingIdentity(clientReturning(HttpStatusCode.InternalServerError), identity)
        assertIs<IdentityPingResult.Unreachable>(result)
    }

    @Test
    fun requestThrows_isUnreachable_withDetail() = runTest {
        // Offline / DNS / timeout / connection refused — a connectivity problem, NOT a verdict
        // on the ID. The old code wrongly called this "are you sure it's a Homebase ID?".
        val result = pingIdentity(clientThrowing(), identity)
        assertIs<IdentityPingResult.Unreachable>(result)
        // The raw cause is carried for the "Show error details" toggle.
        assertTrue(result.detail.contains("simulated network failure"), "detail was: ${result.detail}")
    }

    @Test
    fun tlsHandshakeFailure_isTlsError_withRawCause() = runTest {
        // A VPN/ad-blocker/AV intercepting HTTPS → its own untrusted cert → handshake fails.
        // This must be its own bucket so the UI can name the likely cause, not just "try again".
        val result = pingIdentity(clientThrowingTls(), identity)
        assertIs<IdentityPingResult.TlsError>(result)
        assertTrue(result.detail.contains("Trust anchor"), "detail was: ${result.detail}")
    }
}
