package id.homebase.api.client.drives.cache

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import id.homebase.api.client.KeyHeader
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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

    private val logCollector = CollectingLogWriter()

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

        logCollector.entries.clear()
        Logger.setLogWriters(listOf(logCollector))
    }

    @AfterTest
    fun tearDown() {
        httpClient.close()
        runCatching {
            Files.walk(java.nio.file.Path.of(tempDir))
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
        Logger.setLogWriters(listOf(platformLogWriter()))
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

    @Test
    fun `thumb fetch emits ThumbIO breadcrumbs for network-fetch cache-write and cache-hit`() = runTest {
        provider.getThumbBytesRaw(driveId, fileId, key, 100, 100)

        assertTrue(
            logCollector.hasMessage(tag = "ThumbIO", substring = "thumb network-fetch"),
            "expected ThumbIO network-fetch breadcrumb; got: ${logCollector.messages("ThumbIO")}"
        )
        assertTrue(
            logCollector.hasMessage(tag = "ThumbIO", substring = "thumb cache-write"),
            "expected ThumbIO cache-write breadcrumb; got: ${logCollector.messages("ThumbIO")}"
        )

        // Second call must hit the disk cache and log accordingly.
        logCollector.entries.clear()
        provider.getThumbBytesRaw(driveId, fileId, key, 100, 100)

        assertTrue(
            logCollector.hasMessage(tag = "ThumbIO", substring = "thumb cache-hit"),
            "expected ThumbIO cache-hit breadcrumb; got: ${logCollector.messages("ThumbIO")}"
        )
    }

    @Test
    fun `poisoned cache entry with non-2xx status is visible in the decrypt failure log`() = runTest {
        // Populate the cache with a normal 200 response, then rewrite the on-disk
        // data file with a valid-format ByteApiResponse whose status is 500.
        // Such entries should not exist post-61ebe154 (the write-guard blocks them),
        // but pre-fix code paths could have produced them — so a user upgrading
        // past the fix might still carry one on disk. This test documents the
        // failure mode and pins the diagnostic log to the right context.
        val thumbDir = Path.of(tempDir, "homebase-thumbs")
        val filesBefore = snapshotFiles(thumbDir)

        provider.getThumbBytesRaw(driveId, fileId, key, 100, 100)

        val dataFiles = snapshotFiles(thumbDir) - filesBefore
        assertTrue(dataFiles.isNotEmpty(), "populate step should have written at least one data file")

        // 13 bytes is deliberately not a multiple of the AES block size so
        // decryptUsingKeyHeader fails loudly instead of returning garbage.
        val poisonedPayload = ByteArray(13) { 0xAB.toByte() }
        for (file in dataFiles) {
            DataOutputStream(Files.newOutputStream(file)).use { out ->
                out.writeInt(500)                     // status
                out.writeInt("text/html".length)      // contentType length
                out.writeBytes("text/html")           // contentType (ascii => utf8)
                out.writeByte(1)                      // payloadEncrypted = true
                out.write(poisonedPayload)            // "encrypted" payload
            }
        }
        logCollector.entries.clear()

        val keyHeader = KeyHeader.newRandom16()
        // Using the decrypted entry point — this is what loadThumbnail calls.
        assertFailsWith<Exception> {
            provider.getThumbBytesDecrypted(driveId, fileId, key, keyHeader, 100, 100)
        }

        assertTrue(
            logCollector.hasError(tag = "ThumbIO", substring = "thumb decrypt FAILED"),
            "expected decrypt FAILED error log; got: ${logCollector.messages("ThumbIO")}"
        )
        assertTrue(
            logCollector.messages("ThumbIO").any { it.contains("status=500") },
            "error log must include the poisoned status for triage; got: ${logCollector.messages("ThumbIO")}"
        )
        assertTrue(
            logCollector.messages("ThumbIO").any { it.contains("contentType=text/html") },
            "error log must include the poisoned contentType for triage; got: ${logCollector.messages("ThumbIO")}"
        )
    }

    @Test
    fun `corrupted disk cache entry is logged and call falls through to the network`() = runTest {
        // Populate the thumb cache with a real 200 response.
        provider.getThumbBytesRaw(driveId, fileId, key, 100, 100)
        val countAfterPopulate = requestCount

        // Truncate every file under the thumb cache dir to simulate a corrupted
        // on-disk entry (short read in readBytesResponse => EOFException / NPE).
        val thumbDir = Path.of(tempDir, "homebase-thumbs")
        Files.walk(thumbDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { Files.write(it, ByteArray(0)) }
        }
        logCollector.entries.clear()

        val second = provider.getThumbBytesRaw(driveId, fileId, key, 100, 100)

        assertEquals(200, second.status, "caller should still receive bytes via network fallback")
        assertTrue(
            requestCount > countAfterPopulate,
            "corrupted cache must fall through to the network"
        )
        assertTrue(
            logCollector.hasError(tag = "ThumbIO", substring = "thumb cache-read FAILED"),
            "expected error log for cache-read corruption; got: ${logCollector.messages("ThumbIO")}"
        )
    }

    /**
     * Reproduces the Android NPE that fires 335× after logout: a concurrent
     * `clearCaches()` on one thread races against in-flight `getThumbBytesRaw()`
     * on another, poisoning the FileKache instance the reader is holding.
     *
     * Uses `runBlocking` (not `runTest`) — we need real parallelism across
     * threads, which `runTest`'s virtual-time scheduler deliberately does
     * not provide.
     */
    @Test
    fun `concurrent clearCaches during thumb fetch does not propagate exceptions`() = runBlocking {
        // Warm the cache so reads/writes are exercised (not just fresh inits).
        provider.getThumbBytesRaw(driveId, fileId, key, 100, 100)

        val iterations = 300
        val fetcherErrors = mutableListOf<Throwable>()
        val clearerErrors = mutableListOf<Throwable>()

        val fetcher = async(Dispatchers.Default) {
            repeat(iterations) {
                try {
                    provider.getThumbBytesRaw(driveId, fileId, key, 100, 100)
                } catch (e: NotFoundException) {
                    // Acceptable — the race window can return EMPTY_404 or
                    // throw for a just-cleared cache under some code paths.
                } catch (e: Throwable) {
                    synchronized(fetcherErrors) { fetcherErrors += e }
                }
                yield()
            }
        }

        val clearer = async(Dispatchers.Default) {
            repeat(iterations) {
                try {
                    provider.clearCaches()
                } catch (e: Throwable) {
                    synchronized(clearerErrors) { clearerErrors += e }
                }
                yield()
            }
        }

        awaitAll(fetcher, clearer)

        assertTrue(
            fetcherErrors.isEmpty(),
            "fetcher must not see any unhandled exceptions while clearCaches runs " +
                "(got ${fetcherErrors.size}: ${fetcherErrors.firstOrNull()})"
        )
        assertTrue(
            clearerErrors.isEmpty(),
            "clearCaches must not throw (got ${clearerErrors.size}: ${clearerErrors.firstOrNull()})"
        )
    }

    /**
     * Positive assertion: after clearCaches, the next fetch must actually
     * reach the network. Protects against a future regression where we e.g.
     * null the kache reference but forget to delete the on-disk directory.
     */
    @Test
    fun `clearCaches removes cached bytes so next call re-fetches from network`() = runTest {
        provider.getThumbBytesRaw(driveId, fileId, key, 100, 100)
        val countAfterPopulate = requestCount

        provider.clearCaches()

        provider.getThumbBytesRaw(driveId, fileId, key, 100, 100)
        assertTrue(
            requestCount > countAfterPopulate,
            "after clearCaches, next call must reach the network (requestCount unchanged => cache was not actually cleared)"
        )
    }
}

/** Snapshot every regular file under [dir] (recursively), skipping FileKache's journal. */
private fun snapshotFiles(dir: Path): Set<Path> {
    if (!Files.exists(dir)) return emptySet()
    return Files.walk(dir).use { stream ->
        stream
            .filter { Files.isRegularFile(it) }
            .filter { !it.fileName.toString().startsWith("journal") }
            .toList()
            .toSet()
    }
}

/** Kermit LogWriter that captures entries for test assertions. */
private class CollectingLogWriter : LogWriter() {
    data class Entry(val severity: Severity, val tag: String, val message: String, val throwable: Throwable?)

    val entries: MutableList<Entry> = mutableListOf()

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        entries += Entry(severity, tag, message, throwable)
    }

    fun hasMessage(tag: String, substring: String): Boolean =
        entries.any { it.tag == tag && it.message.contains(substring) }

    fun hasError(tag: String, substring: String): Boolean =
        entries.any { it.tag == tag && it.severity == Severity.Error && it.message.contains(substring) }

    fun messages(tag: String): List<String> =
        entries.filter { it.tag == tag }.map { "[${it.severity}] ${it.message}" }
}
