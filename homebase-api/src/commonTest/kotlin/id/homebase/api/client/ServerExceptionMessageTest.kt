package id.homebase.api.client

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.follow.FollowNotificationType
import id.homebase.api.client.follow.FollowProvider
import id.homebase.api.client.follow.FollowRequest
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerExceptionMessageTest {

    private suspend fun providerRespondingWith(status: HttpStatusCode, body: String): FollowProvider {
        val cm = CredentialsManager()
        cm.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId("test.homebase.id"),
                clientAccessToken = "fake-token",
                sharedSecret = SecureByteArray(ByteArray(16)),
            )
        )
        val engine = MockEngine {
            respond(
                body,
                status,
                headersOf(HttpHeaders.ContentType, ContentType.Application.ProblemJson.toString()),
            )
        }
        return FollowProvider(HttpClient(engine), cm)
    }

    private val request = FollowRequest(
        odinId = OdinId("frodo.dotyou.cloud"),
        notificationType = FollowNotificationType.AllNotifications,
    )

    @Test
    fun serverError_messageCarriesCorrelationIdAndTitle() = runTest {
        val provider = providerRespondingWith(
            HttpStatusCode.InternalServerError,
            """{"type":"https://tools.ietf.org/html/rfc7231","title":"Internal Server Error","status":500,"correlationId":"9c0b1703-d648-4027-b993-7ab0a54eca99","errorCode":"unhandledScenario"}""",
        )

        val e = assertFailsWith<ServerException> { provider.follow(request) }

        assertEquals("9c0b1703-d648-4027-b993-7ab0a54eca99", e.correlationId)
        assertEquals(
            "Internal Server Error (status=500, errorCode=unhandledScenario, correlationId=9c0b1703-d648-4027-b993-7ab0a54eca99)",
            e.message,
        )
    }

    @Test
    fun serverError_unparsableBody_stillHasSensibleMessage() = runTest {
        val provider = providerRespondingWith(HttpStatusCode.BadGateway, "<html>bad gateway</html>")

        val e = assertFailsWith<ServerException> { provider.follow(request) }

        assertEquals("Server error (status=502)", e.message)
    }
}
