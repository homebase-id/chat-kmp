package id.homebase.core.ui.screens.card

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.profile.ProfileProvider
import id.homebase.api.client.profile.ProfileRepository
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.JsonElement

/** The real ProfileRepository + providers over a Ktor MockEngine that decrypts what the app PUTs. */
class CardWireHarness(private val putReply: (Int) -> Reply = { Reply.Ok }) {
    sealed interface Reply {
        data object Ok : Reply
        class Problem(val status: Int, val json: String) : Reply
    }

    private val recorded = java.util.concurrent.CopyOnWriteArrayList<JsonElement>()
    val putBodies: List<JsonElement> get() = recorded.toList()
    private val putCount = java.util.concurrent.atomic.AtomicInteger()
    val puts: Int get() = putCount.get()

    private val secret = SecureByteArray("0123456789abcdef".encodeToByteArray())

    private val engine = MockEngine { request ->
        val json = headersOf("Content-Type" to listOf(ContentType.Application.Json.toString()))
        when {
            request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/profile/attributes") -> {
                val plain = CryptoHelper.decryptContentAsString((request.body as TextContent).text, secret.unsafeBytes)
                recorded += OdinSystemSerializer.json.parseToJsonElement(plain)
                when (val reply = putReply(putCount.incrementAndGet())) {
                    Reply.Ok -> respond(
                        """{"id":"11111111-1111-4111-8111-111111111111","versionTag":"22222222-2222-4222-8222-222222222222"}""",
                        HttpStatusCode.OK,
                        json,
                    )
                    is Reply.Problem -> respond(reply.json, HttpStatusCode.fromValue(reply.status), json)
                }
            }
            request.url.encodedPath.endsWith("/query-batch") -> respond("""{"searchResults":[]}""", HttpStatusCode.OK, json)
            else -> error("unexpected ${request.method} ${request.url}")
        }
    }

    suspend fun profileRepository(): ProfileRepository {
        val cm = CredentialsManager()
        val creds = ApiCredentials.create(
            domain = OdinId("test.homebase.id"),
            clientAccessToken = "test-token",
            sharedSecret = secret,
        )
        cm.storeCredentials(creds)
        cm.setActiveCredentials(creds)
        val client = HttpClient(engine)
        return ProfileRepository(DriveQueryProvider(client, cm), ProfileProvider(client, cm))
    }

    suspend fun cardRepository() = CardRepository(ProfileRepositoryCardStore(profileRepository()))

    companion object {
        const val UNKNOWN_CARD_TYPE_400 =
            """{"type":"https://tools.ietf.org/html/rfc9110#section-15.5.1","title":"Unknown profile attribute type 9832dc5dd4ba12dd60acb853e7588f49","status":400,"errorCode":"argumentError","correlationId":"abc"}"""
        const val UNKNOWN_PHOTO_TYPE_400 =
            """{"title":"Unknown profile attribute type 5c1bfc6b6a0b4ed88b1c1f5a5b4e0f3a","status":400,"errorCode":"argumentError"}"""
    }
}
