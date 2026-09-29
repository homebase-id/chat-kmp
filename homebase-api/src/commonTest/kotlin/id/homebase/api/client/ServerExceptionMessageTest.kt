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
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private val cid = "9c0b1703-d648-4027-b993-7ab0a54eca99"

    private fun problemJson(status: Int, title: String, errorCode: String) =
        """{"type":"https://tools.ietf.org/html/rfc7231","title":"$title","status":$status,"correlationId":"$cid","errorCode":"$errorCode"}"""

    @Test
    fun clientError_400_titleLeadsAndCarriesCorrelationId() = runTest {
        val provider = providerRespondingWith(
            HttpStatusCode.BadRequest,
            problemJson(400, "Missing version tag", "missingVersionTag"),
        )

        val e = assertFailsWith<ClientException> { provider.follow(request) }

        assertEquals(cid, e.correlationId)
        assertEquals(
            "Missing version tag (status=400, errorCode=missingVersionTag, correlationId=$cid)",
            e.message,
        )
        assertTrue(e.message!!.startsWith("Missing version tag"))
    }

    @Test
    fun forbidden_403_carriesCorrelationId() = runTest {
        val provider = providerRespondingWith(
            HttpStatusCode.Forbidden,
            problemJson(403, "Forbidden", "unhandledScenario"),
        )

        val e = assertFailsWith<ForbiddenException> { provider.follow(request) }

        assertEquals(cid, e.correlationId)
        assertEquals("Forbidden (status=403, errorCode=unhandledScenario, correlationId=$cid)", e.message)
    }

    @Test
    fun notFound_404_carriesCorrelationId() = runTest {
        val provider = providerRespondingWith(
            HttpStatusCode.NotFound,
            problemJson(404, "Not Found", "unhandledScenario"),
        )

        val e = assertFailsWith<NotFoundException> { provider.follow(request) }

        assertEquals(cid, e.correlationId)
        assertEquals("Not found (status=404, errorCode=unhandledScenario, correlationId=$cid)", e.message)
    }

    @Test
    fun unauthorized_401_carriesCorrelationId() = runTest {
        val provider = providerRespondingWith(
            HttpStatusCode.Unauthorized,
            problemJson(401, "Unauthorized", "unhandledScenario"),
        )

        val e = assertFailsWith<UnauthorizedException> { provider.follow(request) }

        assertEquals(cid, e.correlationId)
        assertTrue(e.message!!.contains("correlationId=$cid"))
    }

    @Test
    fun notFound_emptyBody_hasStatusOnly() = runTest {
        val provider = providerRespondingWith(HttpStatusCode.NotFound, "")

        val e = assertFailsWith<NotFoundException> { provider.follow(request) }

        assertNull(e.correlationId)
        assertEquals("Not found (status=404)", e.message)
    }

    @Test
    fun forbidden_unparsableBody_hasStatusOnly() = runTest {
        val provider = providerRespondingWith(HttpStatusCode.Forbidden, "<html>nope</html>")

        val e = assertFailsWith<ForbiddenException> { provider.follow(request) }

        assertNull(e.correlationId)
        assertEquals("Forbidden (status=403)", e.message)
    }

    @Test
    fun networkException_messageUnchanged() {
        assertEquals("Network failure: boom", NetworkException(RuntimeException("boom")).message)
    }
}
