package id.homebase.api.client.drives.files

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.profile.FakeFileOperationsProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** Only odin-core's own 404 says a file isn't there; a proxy's in front of it says nothing. */
class FileHeaderNotFoundTest {

    private val secret = ByteArray(16) { 5 }
    private val odin = headersOf("X-Odin-Version", "1.0")
    private val problem = """{"title":"Not Found","status":404}"""

    private suspend fun header(headers: Headers, body: String): HomebaseFile? {
        val credentials = CredentialsManager()
        credentials.setActiveCredentials(ApiCredentials.create(OdinId("frodo.dotyou.cloud"), "token", SecureByteArray(secret.copyOf())))
        val http = HttpClient(MockEngine { respond(body, HttpStatusCode.NotFound, headers) })
        val files = DriveFileProvider(http, credentials, DriveFileProviderCached(http, credentials, FakeFileOperationsProvider()))
        return files.getFileHeader(Uuid.random(), Uuid.random())
    }

    @Test
    fun aSealedAnswerFromTheIdentityIsNotThere() = runTest {
        // No X-SSE: a browser doesn't see it, so the envelope alone tells it was sealed.
        val sealed = OdinSystemSerializer.serialize(CryptoHelper.encryptData(problem, secret))
        assertNull(header(odin, sealed))
    }

    @Test
    fun problemDetailsFromTheIdentityIsNotThere() = runTest {
        assertNull(header(odin, problem))
    }

    @Test
    fun anythingElseIsAFailureThatSaysNothingAboutTheFile() = runTest {
        val notOdin = listOf(
            Headers.Empty to problem,
            odin to "",
            odin to "<html>maintenance</html>",
        )
        for ((headers, body) in notOdin) {
            val e = assertFailsWith<NotFoundException> { header(headers, body) }
            assertFalse(e.fromIdentity, body)
        }
    }
}
