package id.homebase.api.client.upgrade

import id.homebase.api.client.NetworkException
import id.homebase.api.client.UnauthorizedException
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.isTransientNetworkFailure
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdentityUpgradeProviderTest {

    private val testDomain = OdinId("test.homebase.id")

    private suspend fun createProvider(engine: MockEngine): IdentityUpgradeProvider {
        val cm = CredentialsManager()
        val creds = ApiCredentials.create(
            domain = testDomain,
            clientAccessToken = "test-token",
            sharedSecret = SecureByteArray("test-secret".encodeToByteArray()),
        )
        cm.storeCredentials(creds)
        cm.setActiveCredentials(creds)
        return IdentityUpgradeProvider(HttpClient(engine), cm)
    }

    @Test
    fun returnsRequired_whenUpgradeHeaderPresent() = runTest {
        val engine = MockEngine { request ->
            assertEquals("Bearer test-token", request.headers["Authorization"])
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    "X-REQUIRES-UPGRADE" to listOf("1"),
                    "Content-Type" to listOf(ContentType.Application.Json.toString()),
                ),
            )
        }
        assertEquals(UpgradeStatus.REQUIRED, createProvider(engine).checkUpgradeStatus())
    }

    @Test
    fun returnsNone_whenNoUpgradeHeaders() = runTest {
        val engine = MockEngine {
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type" to listOf(ContentType.Application.Json.toString())),
            )
        }
        assertEquals(UpgradeStatus.NONE, createProvider(engine).checkUpgradeStatus())
    }

    @Test
    fun returnsRunning_whenUpgradeRunningHeaderPresent() = runTest {
        val engine = MockEngine {
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    "X-UPGRADE-RUNNING" to listOf("1"),
                    "Content-Type" to listOf(ContentType.Application.Json.toString()),
                ),
            )
        }
        assertEquals(UpgradeStatus.RUNNING, createProvider(engine).checkUpgradeStatus())
    }

    @Test
    fun returnsRunning_whenBothHeadersPresent() = runTest {
        val engine = MockEngine {
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    "X-REQUIRES-UPGRADE" to listOf("1"),
                    "X-UPGRADE-RUNNING" to listOf("1"),
                    "Content-Type" to listOf(ContentType.Application.Json.toString()),
                ),
            )
        }
        assertEquals(UpgradeStatus.RUNNING, createProvider(engine).checkUpgradeStatus())
    }

    @Test
    fun throwsUnauthorized_on401() = runTest {
        val engine = MockEngine {
            respond(
                content = "{}",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf("Content-Type" to listOf(ContentType.Application.Json.toString())),
            )
        }
        assertFailsWith<UnauthorizedException> {
            createProvider(engine).checkUpgradeStatus()
        }
    }

    @Test
    fun throwsTypedNetworkExceptionOnTransportFailure() = runTest {
        // A raw transport IOException from the engine must surface as the typed,
        // catchable NetworkException (not a bare IOException), with the original
        // exception preserved as the cause for diagnosis.
        val engine = MockEngine { throw java.io.IOException("timeout") }
        val thrown = assertFailsWith<NetworkException> {
            createProvider(engine).checkUpgradeStatus()
        }
        // Cause preserved (Ktor may rewrap the engine's IOException, so assert the
        // type/chain rather than instance identity) so the real stack survives.
        assertTrue(thrown.cause is java.io.IOException, "cause should be the transport IOException")
        assertTrue(thrown.isTransientNetworkFailure())
    }
}
