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

    @Test
    fun http200_isOk() = runTest {
        assertEquals(
            IdentityPingResult.Ok,
            pingIdentity(clientReturning(HttpStatusCode.OK), identity),
        )
    }

    @Test
    fun http404_isNotHomebase() = runTest {
        // Reached a server, but it isn't answering as a Homebase identity.
        assertEquals(
            IdentityPingResult.NotHomebase,
            pingIdentity(clientReturning(HttpStatusCode.NotFound), identity),
        )
    }

    @Test
    fun http503_isNotHomebase() = runTest {
        assertEquals(
            IdentityPingResult.NotHomebase,
            pingIdentity(clientReturning(HttpStatusCode.ServiceUnavailable), identity),
        )
    }

    @Test
    fun requestThrows_isUnreachable() = runTest {
        // Offline / DNS / timeout / TLS / connection refused — a connectivity problem, NOT
        // a verdict on the ID. The old code wrongly called this "are you sure it's a Homebase ID?".
        assertEquals(
            IdentityPingResult.Unreachable,
            pingIdentity(clientThrowing(), identity),
        )
    }
}
