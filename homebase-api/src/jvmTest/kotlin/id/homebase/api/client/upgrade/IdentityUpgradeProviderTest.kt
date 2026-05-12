package id.homebase.api.client.upgrade

import id.homebase.api.client.UnauthorizedException
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
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
import kotlin.test.assertFalse
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
    fun returnsTrue_whenUpgradeHeaderPresent() = runTest {
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
        assertTrue(createProvider(engine).isUpgradeRequired())
    }

    @Test
    fun returnsFalse_whenUpgradeHeaderAbsent() = runTest {
        val engine = MockEngine {
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type" to listOf(ContentType.Application.Json.toString())),
            )
        }
        assertFalse(createProvider(engine).isUpgradeRequired())
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
            createProvider(engine).isUpgradeRequired()
        }
    }

    @Test
    fun throwsOnNetworkError() = runTest {
        val engine = MockEngine { throw java.io.IOException("timeout") }
        assertFailsWith<java.io.IOException> {
            createProvider(engine).isUpgradeRequired()
        }
    }
}
