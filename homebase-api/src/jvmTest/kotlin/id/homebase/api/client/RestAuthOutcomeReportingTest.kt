package id.homebase.api.client

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.AuthFailureReporter
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val OWN_HOST = "test.homebase.id"

// The CDN fallback in PeerFileByGlobalTransitProvider: no bearer token, and 401 is its ordinary
// answer for a peer drive with AllowCdn off (the default). Counting those would log a user out on
// the first feed scroll.
private const val CDN_HOST = "cdn.ravenhosting.cloud"

class RestAuthOutcomeReportingTest {

    private class Recorder : AuthFailureReporter {
        var unauthorized = 0
        var authorized = 0
        override fun onRestUnauthorized() {
            unauthorized++
        }

        override fun onRestAuthorized() {
            authorized++
        }
    }

    private class TestProvider(
        httpClient: HttpClient,
        credentialsManager: CredentialsManager,
    ) : OdinApiProviderBase(httpClient, credentialsManager) {
        suspend fun read(url: String): ApiResponse =
            request({ httpClient.get(url) }, secret = null)

        suspend fun readBytes(url: String): ByteApiResponse =
            requestBytes { httpClient.get(url) }
    }

    private suspend fun setup(
        vararg statusByHost: Pair<String, HttpStatusCode>,
    ): Pair<TestProvider, Recorder> {
        val statuses = statusByHost.toMap()
        val engine = MockEngine { request ->
            respond(
                content = "{}",
                status = statuses[request.url.host] ?: HttpStatusCode.OK,
                headers = headersOf(
                    "Content-Type" to listOf(ContentType.Application.Json.toString()),
                ),
            )
        }
        val cm = CredentialsManager()
        cm.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId(OWN_HOST),
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray("test-secret".encodeToByteArray()),
            )
        )
        val recorder = Recorder()
        cm.setAuthFailureReporter(recorder)
        return TestProvider(HttpClient(engine), cm) to recorder
    }

    @Test
    fun reportsUnauthorizedOnIdentityHost401() = runTest {
        val (provider, recorder) = setup(OWN_HOST to HttpStatusCode.Unauthorized)

        provider.read("https://$OWN_HOST/api/v2/anything")

        assertEquals(1, recorder.unauthorized, "a REST 401 must reach the auth layer")
        assertEquals(0, recorder.authorized)
    }

    @Test
    fun reportsAuthorizedOnIdentityHost2xx() = runTest {
        val (provider, recorder) = setup(OWN_HOST to HttpStatusCode.OK)

        provider.read("https://$OWN_HOST/api/v2/anything")

        assertEquals(1, recorder.authorized, "a 2xx is the proof of life that resets the streak")
        assertEquals(0, recorder.unauthorized)
    }

    @Test
    fun reportsOncePerResponseIncludingByteReads() = runTest {
        val (provider, recorder) = setup(OWN_HOST to HttpStatusCode.Unauthorized)

        provider.read("https://$OWN_HOST/api/v2/anything")
        provider.readBytes("https://$OWN_HOST/api/v2/payload")

        assertEquals(2, recorder.unauthorized, "byte reads 401 too, and each response counts once")
    }

    @Test
    fun ignoresA401FromAHostWeDidNotAuthenticateAgainst() = runTest {
        val (provider, recorder) = setup(CDN_HOST to HttpStatusCode.Unauthorized)

        provider.readBytes("https://$CDN_HOST/?forward=whatever")

        assertEquals(0, recorder.unauthorized, "an AllowCdn-off 401 says nothing about our token")
        assertEquals(0, recorder.authorized)
    }

    @Test
    fun ignoresA2xxFromAHostWeDidNotAuthenticateAgainst() = runTest {
        val (provider, recorder) = setup(CDN_HOST to HttpStatusCode.OK)

        provider.readBytes("https://$CDN_HOST/?forward=whatever")

        assertEquals(0, recorder.authorized, "a CDN hit must not clear evidence of a dead token")
    }

    @Test
    fun ignoresStatusesThatSayNothingAboutTheToken() = runTest {
        val (provider, recorder) = setup(OWN_HOST to HttpStatusCode.Forbidden)

        provider.read("https://$OWN_HOST/api/v2/anything")

        assertEquals(0, recorder.unauthorized)
        assertEquals(0, recorder.authorized)
    }
}
