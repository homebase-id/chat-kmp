@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.api.client.follow

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FollowProviderTest {

    // 16-byte zero shared secret; the MockEngine decrypts captured request bodies with it.
    private val sharedSecret = ByteArray(16)

    private suspend fun creds(): CredentialsManager {
        val cm = CredentialsManager()
        cm.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId("test.homebase.id"),
                clientAccessToken = "fake-token",
                sharedSecret = SecureByteArray(sharedSecret.copyOf()),
            )
        )
        return cm
    }

    private suspend fun decrypt(request: HttpRequestData): String {
        val envelope = request.body.toByteArray().decodeToString()
        return CryptoHelper.decryptContentAsString(envelope, sharedSecret)
    }

    @Test
    fun follow_postsFollowRequestBodyToFollowEndpoint() = runTest {
        var capturedPath: String? = null
        var capturedBody: String? = null

        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            capturedBody = decrypt(request)
            respond("", HttpStatusCode.OK)
        }
        val provider = FollowProvider(HttpClient(engine), creds())

        provider.follow(
            FollowRequest(
                odinId = OdinId("frodo.dotyou.cloud"),
                notificationType = FollowNotificationType.AllNotifications,
            )
        )

        assertEquals("/api/v2/followers/follow", capturedPath)
        val body = assertNotNull(capturedBody)
        val sent = OdinSystemSerializer.deserialize<FollowRequest>(body)
        assertEquals(OdinId("frodo.dotyou.cloud"), sent.odinId)
        assertEquals(FollowNotificationType.AllNotifications, sent.notificationType)
    }

    @Test
    fun fetchFollowing_parsesCursoredListFromIdentitiesIFollow() = runTest {
        var capturedPath: String? = null

        val body = OdinSystemSerializer.serialize(
            FollowPageResponse(
                results = listOf("sam.dotyou.cloud", "merry.dotyou.cloud"),
                cursorState = "next-cursor-abc",
            )
        )
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val provider = FollowProvider(HttpClient(engine), creds())

        val page = provider.fetchFollowing(cursor = null, max = 100)

        assertEquals("/api/v2/followers/IdentitiesIFollow", capturedPath)
        assertEquals(listOf("sam.dotyou.cloud", "merry.dotyou.cloud"), page.results)
        assertEquals("next-cursor-abc", page.cursorState)
        assertTrue(page.results.size == 2)
    }
}
