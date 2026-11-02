package id.homebase.api.client.drives.files.reactions

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class DriveFileGroupReactionProviderTest {

    private val methods = mutableListOf<HttpMethod>()
    private var body = "{}"
    private val credentialsManager = CredentialsManager()
    private val provider = DriveFileGroupReactionProvider(
        HttpClient(MockEngine { request ->
            methods += request.method
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }),
        credentialsManager,
    )

    @BeforeTest
    fun signIn() = runBlocking {
        credentialsManager.setActiveCredentials(ApiCredentials.create(OdinId("frodo.test"), "token", SecureByteArray(ByteArray(16))))
    }

    @Test
    fun theSummaryIsAGetOfAListOfCounts() = runTest {
        body = """{"reactions":[{"reactionContent":"{\"emoji\":\"👍\"}","count":2}],"total":2}"""

        val summary = provider.getReactionSummary(Uuid.random(), Uuid.random())

        assertEquals(listOf(HttpMethod.Get), methods)
        assertEquals(listOf(GroupReactionCount("""{"emoji":"👍"}""", 2)), summary.reactions)
    }

    @Test
    fun anIdentitysReactionsAreAGet() = runTest {
        body = """["{\"emoji\":\"👍\"}"]"""

        assertEquals(listOf("""{"emoji":"👍"}"""), provider.listReactionsByIdentity(Uuid.random(), Uuid.random(), OdinId("sam.test")))
        assertEquals(listOf(HttpMethod.Get), methods)
    }
}
