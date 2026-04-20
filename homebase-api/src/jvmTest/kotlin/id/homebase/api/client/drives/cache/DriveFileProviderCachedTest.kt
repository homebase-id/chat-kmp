package id.homebase.api.client.drives.cache

import id.homebase.api.client.NotFoundException
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/**
 * Verifies that [DriveFileProviderCached] only caches genuine 404 (NotFoundException) responses
 * in its notFoundCache, and never caches transient failures such as network errors or 5xx responses.
 */
class DriveFileProviderCachedTest {

    private var requestCount = 0
    private var nextException: Exception? = null
    private var nextStatus = HttpStatusCode.OK

    private val mockEngine = MockEngine { _ ->
        requestCount++
        nextException?.let { e -> throw e }
        respond("", nextStatus)
    }

    private val httpClient = HttpClient(mockEngine)
    private val credentialsManager = CredentialsManager()
    private var tempDir: String = ""
    private lateinit var provider: DriveFileProviderCached

    private val driveId = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val fileId  = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val key = "photo"

    @BeforeTest
    fun setup() = runBlocking {
        tempDir = Files.createTempDirectory("hb-cache-test").toString()

        credentialsManager.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId("frodobaggins.me"),
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray(ByteArray(32) { 0x01 })
            )
        )

        provider = DriveFileProviderCached(
            httpClient = httpClient,
            credentialsManager = credentialsManager,
            fileOperationsProvider = object : FileOperationsProvider {
                override fun getCacheDirectory() = tempDir
                override fun openFileInput(path: String): InputProvider = error("not used in tests")
                override suspend fun readFileBytes(path: String): ByteArray = error("not used in tests")
                override fun deleteTempFile(path: String) = false
                override fun getFileSize(path: String) = 0L
                override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used in tests")
                override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used in tests")
            }
        )

        requestCount = 0
        nextException = null
        nextStatus = HttpStatusCode.OK
    }

    @AfterTest
    fun tearDown() {
        httpClient.close()
        runCatching {
            Files.walk(java.nio.file.Path.of(tempDir))
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `404 response is cached and subsequent call skips the network`() = runTest {
        nextStatus = HttpStatusCode.NotFound

        assertFailsWith<NotFoundException> {
            provider.getThumbBytesRaw(driveId, fileId, key, 100, 100)
        }
        val countAfterFirst = requestCount

        // Switch mock to return 200 — the notFoundCache should intercept before reaching network
        nextStatus = HttpStatusCode.OK
        val second = provider.getThumbBytesRaw(driveId, fileId, key, 100, 100)

        assertEquals(countAfterFirst, requestCount, "notFoundCache must prevent a second network call for 404")
        assertEquals(404, second.status, "Cached result should be EMPTY_404")
    }

    @Test
    fun `network error is not cached — subsequent call retries and succeeds`() = runTest {
        nextException = IOException("Network unavailable")

        assertFailsWith<Exception> {
            provider.getThumbBytesRaw(driveId, fileId, key, 100, 100)
        }
        val countAfterFirst = requestCount

        // Restore connectivity
        nextException = null
        nextStatus = HttpStatusCode.OK
        val second = provider.getThumbBytesRaw(driveId, fileId, key, 100, 100)

        assertEquals(countAfterFirst + 1, requestCount, "IOException must not be cached — second call must reach the network")
        assertEquals(200, second.status)
    }

    @Test
    fun `500 server error is not cached — subsequent call retries and succeeds`() = runTest {
        nextStatus = HttpStatusCode.InternalServerError

        assertFailsWith<Exception> {
            provider.getThumbBytesRaw(driveId, fileId, key, 100, 100)
        }
        val countAfterFirst = requestCount

        // Server recovers
        nextStatus = HttpStatusCode.OK
        val second = provider.getThumbBytesRaw(driveId, fileId, key, 100, 100)

        assertEquals(countAfterFirst + 1, requestCount, "500 must not be cached — second call must reach the network")
        assertEquals(200, second.status)
    }
}
