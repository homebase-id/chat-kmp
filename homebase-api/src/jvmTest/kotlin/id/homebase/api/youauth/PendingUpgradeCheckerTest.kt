package id.homebase.api.youauth

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingUpgradeCheckerTest {

    private val testDomain = OdinId("test.homebase.id")

    private suspend fun createChecker(engine: MockEngine, withCredentials: Boolean = true): PendingUpgradeChecker {
        val credentialsManager = CredentialsManager()
        if (withCredentials) {
            val creds = ApiCredentials.create(
                domain = testDomain,
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray("test-secret".encodeToByteArray()),
            )
            credentialsManager.storeCredentials(creds)
            credentialsManager.setActiveCredentials(creds)
        }
        return PendingUpgradeChecker(HttpClient(engine), credentialsManager)
    }

    @Test
    fun returnsTrue_whenHeaderPresent() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.OK, headersOf("X-REQUIRES-UPGRADE" to listOf("1"))) }
        assertTrue(createChecker(engine).isUpgradeRequired())
    }

    @Test
    fun returnsTrue_whenHeaderPresentLowercase() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.OK, headersOf("x-requires-upgrade" to listOf("true"))) }
        assertTrue(createChecker(engine).isUpgradeRequired())
    }

    @Test
    fun returnsFalse_whenHeaderAbsent() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.OK) }
        assertFalse(createChecker(engine).isUpgradeRequired())
    }

    @Test
    fun returnsFalse_on401() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        assertFalse(createChecker(engine).isUpgradeRequired())
    }

    @Test
    fun returnsFalse_onNetworkError() = runTest {
        val engine = MockEngine { throw java.io.IOException("timeout") }
        assertFalse(createChecker(engine).isUpgradeRequired())
    }

    @Test
    fun returnsFalse_whenNoActiveCredentials() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.OK, headersOf("X-REQUIRES-UPGRADE" to listOf("1"))) }
        assertFalse(createChecker(engine, withCredentials = false).isUpgradeRequired())
    }
}
