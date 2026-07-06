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
        val result = pingIdentity(clientThrowing(), identity) { null }
        assertIs<IdentityPingResult.Unreachable>(result)
        // The raw cause + the exact host we tried are carried for the "Show error details" toggle.
        assertTrue(result.detail.contains("simulated network failure"), "detail was: ${result.detail}")
        assertTrue(result.detail.contains("sam.homebase.id"), "host must be echoed; was: ${result.detail}")
    }

    @Test
    fun tlsHandshakeFailure_isTlsError_withHostAndIssuer() = runTest {
        // A VPN/ad-blocker/AV intercepting HTTPS → its own untrusted cert → handshake fails.
        // Its own bucket, AND the injected probe names who signed the presented cert.
        val fakeProbe: suspend (String) -> String? = { host ->
            "certificate presented by $host was issued by [CN=AdGuard Personal CA, O=AdGuard]"
        }
        val result = pingIdentity(clientThrowingTls(), identity, fakeProbe)
        assertIs<IdentityPingResult.TlsError>(result)
        assertTrue(result.detail.contains("Trust anchor"), "raw cause; was: ${result.detail}")
        assertTrue(result.detail.contains("sam.homebase.id"), "host must be echoed; was: ${result.detail}")
        assertTrue(result.detail.contains("AdGuard"), "issuer must be named; was: ${result.detail}")
    }

    @Test
    fun tlsHandshakeFailure_probeUnavailable_stillTlsErrorWithHost() = runTest {
        // iOS/web (or a probe error) returns null — we still get TlsError with host + cause.
        val result = pingIdentity(clientThrowingTls(), identity) { null }
        assertIs<IdentityPingResult.TlsError>(result)
        assertTrue(result.detail.contains("sam.homebase.id"), "host must be echoed; was: ${result.detail}")
    }
}
