package id.homebase.api.client

import id.homebase.api.client.auth.CredentialsManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Desktop/CIO offline variant of [TlsInterceptionTest]. On DNS failure / no network the
 * Ktor CIO engine (Desktop, JVM) throws `java.nio.channels.UnresolvedAddressException` — an
 * `IllegalArgumentException`, NOT an `IOException` — so it bypasses the IOException-shaped
 * catch that already covers OkHttp's `UnknownHostException` (Android). This pins the two
 * contracts the crash-containment fix relies on for that shape:
 *
 *  1. `request()` maps the CIO connect-time failure to a typed [NetworkException], preserving
 *     the raw cause.
 *  2. The result classifies as [isTransientNetworkFailure] == true — the signal the platform
 *     uncaught handlers key on to contain it instead of crashing the offline cold start.
 */
class CioOfflineConnectTest {

    /** Minimal concrete provider exposing the protected request() primitive. */
    private class TestProvider(client: HttpClient) :
        OdinApiProviderBase(client, CredentialsManager()) {
        suspend fun ping(): ApiResponse = request(
            block = { httpClient.get("https://queralt.dominion.id/api/v2/health/ping") },
            secret = null,
        )
    }

    /** A client whose every call dies the way CIO does when offline (no DNS resolution). */
    private fun offlineClient(): HttpClient =
        HttpClient(MockEngine { throw UnresolvedAddressException() })

    @Test
    fun apiCall_whenOffline_mapsToTypedNetworkException() = runTest {
        val provider = TestProvider(offlineClient())

        val thrown = assertFailsWith<NetworkException> { provider.ping() }

        // The raw CIO cause is preserved for the crash report.
        assertIs<UnresolvedAddressException>(thrown.cause)
    }

    @Test
    fun cioOfflineFailure_isClassifiedTransient() {
        val raw = UnresolvedAddressException()
        // Raw, and wrapped exactly as the API layer wraps it — both must be "transient" so the
        // platform handlers contain it instead of terminating the process.
        assertTrue(raw.isTransientNetworkFailure())
        assertTrue(NetworkException(raw).isTransientNetworkFailure())
    }
}
