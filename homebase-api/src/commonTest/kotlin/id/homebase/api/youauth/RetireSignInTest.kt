package id.homebase.api.youauth

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RetireSignInTest {

    private val replaced = ApiCredentials.create(OdinId("frodo.example.com"), "old-token", SecureByteArray(ByteArray(16) { 3 }))

    @Test
    fun theReplacedSignInLosesItsPushThenIsDeleted() = runTest {
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            calls += "${request.url.encodedPath} ${request.headers[HttpHeaders.Authorization]}"
            respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        retireSignIn(HttpClient(engine), replaced)

        assertEquals(
            listOf("/api/v2/notify/push/unsubscribe Bearer old-token", "/api/v2/auth/logout Bearer old-token"),
            calls,
        )
    }

    @Test
    fun aFailedUnsubscribeStillDeletesTheSignIn() = runTest {
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            calls += request.url.encodedPath
            respond("", if ("unsubscribe" in request.url.encodedPath) HttpStatusCode.InternalServerError else HttpStatusCode.OK)
        }

        retireSignIn(HttpClient(engine), replaced)

        assertEquals(listOf("/api/v2/notify/push/unsubscribe", "/api/v2/auth/logout"), calls)
    }
}
