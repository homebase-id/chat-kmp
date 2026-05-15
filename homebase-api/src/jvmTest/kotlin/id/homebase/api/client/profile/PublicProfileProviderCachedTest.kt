package id.homebase.api.client.profile

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import id.homebase.api.client.cache.CacheStats
import id.homebase.api.common.OdinId
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that [PublicProfileProviderCached] is hardened against the same
 * FileKache clear/get race that fired in [id.homebase.api.client.drives.cache.DriveFileProviderCached].
 * Mirrors the shape of `DriveFileProviderCachedTest`.
 */
class PublicProfileProviderCachedTest {

    private var requestCount = 0
    private var nextException: Exception? = null
    private var nextStatus = HttpStatusCode.OK
    private val imageBytes = ByteArray(128) { it.toByte() }

    private val mockEngine = MockEngine { _ ->
        requestCount++
        nextException?.let { e -> throw e }
        if (nextStatus == HttpStatusCode.OK) {
            respond(imageBytes, nextStatus)
        } else {
            respond("", nextStatus)
        }
    }

    private val httpClient = HttpClient(mockEngine)
    private var tempDir: String = ""
    private lateinit var provider: PublicProfileProviderCached

    private val logCollector = CollectingLogWriter()

    private val odinId = OdinId("frodobaggins.me")

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("hb-pub-profile-cache-test").toString()

        provider = PublicProfileProviderCached(
            httpClient = httpClient,
            fileOperationsProvider = object : FileOperationsProvider {
                override fun getCacheDirectory() = tempDir
                override fun openFileInput(path: String): InputProvider = error("not used in tests")
                override suspend fun readFileBytes(path: String): ByteArray = error("not used in tests")
                override fun deleteTempFile(path: String) = false
                override fun getFileSize(path: String) = 0L
                override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used in tests")
                override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used in tests")
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
            Files.walk(Path.of(tempDir))
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
        Logger.setLogWriters(listOf(platformLogWriter()))
    }

    @Test
    fun `getPublicImage returns bytes and caches to disk`() = runTest {
        val first = provider.getPublicImage(odinId)
        assertNotNull(first)
        assertEquals(imageBytes.size, first.size)
        assertEquals(1, requestCount)

        // Second call — should hit disk cache, no new network request.
        val second = provider.getPublicImage(odinId)
        assertNotNull(second)
        assertEquals(1, requestCount, "disk cache must prevent a second network call")
    }

    @Test
    fun `corrupted cache entry is logged and call falls through to the network`() = runTest {
        // Populate the image cache with a real 200 response.
        provider.getPublicImage(odinId)
        val countAfterPopulate = requestCount

        // Truncate every file under the image cache dir to simulate a corrupted
        // on-disk entry. readFromDisk will throw (EOF) when it tries to parse it.
        val imageDir = Path.of(tempDir, "homebase-public-images-v2")
        Files.walk(imageDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { Files.write(it, ByteArray(0)) }
        }
        logCollector.entries.clear()

        val second = provider.getPublicImage(odinId)
        assertNotNull(second, "caller should still receive bytes via network fallback")
        assertEquals(imageBytes.size, second.size)
        assertTrue(
            requestCount > countAfterPopulate,
            "corrupted cache must fall through to the network"
        )
        assertTrue(
            logCollector.hasError(tag = "PublicProfileIO", substring = "cache-read FAILED"),
            "expected error log for cache-read corruption; got: ${logCollector.messages("PublicProfileIO")}"
        )
    }

    /**
     * Reproduces the FileKache clear/get race shape in the public profile
     * cache. Same pattern as `concurrent clearCaches during thumb fetch does
     * not propagate exceptions` in DriveFileProviderCachedTest.
     *
     * Uses `runBlocking` (not `runTest`) — we need real parallelism across
     * threads, which `runTest`'s virtual-time scheduler deliberately does
     * not provide.
     */
    @Test
    fun `concurrent clearCaches during fetch does not propagate exceptions`() = runBlocking {
        // Warm the cache so reads/writes are exercised, not just fresh inits.
        provider.getPublicImage(odinId)

        val iterations = 300
        val fetcherErrors = mutableListOf<Throwable>()
        val clearerErrors = mutableListOf<Throwable>()

        val fetcher = async(Dispatchers.Default) {
            repeat(iterations) {
                try {
                    provider.getPublicImage(odinId)
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

    @Test
    fun `404 response is cached and subsequent call skips the network`() = runTest {
        nextStatus = HttpStatusCode.NotFound

        val first = provider.getPublicImage(odinId)
        assertNull(first, "404 must surface as null")
        val countAfterFirst = requestCount

        // Switch to 200 — notFoundCache should intercept before reaching the network.
        nextStatus = HttpStatusCode.OK
        val second = provider.getPublicImage(odinId)

        assertEquals(countAfterFirst, requestCount, "notFoundCache must prevent a second network call for 404")
        assertNull(second, "cached 404 must still surface as null")
    }

    @Test
    fun `5xx response is not cached — subsequent call retries and succeeds`() = runTest {
        nextStatus = HttpStatusCode.InternalServerError

        assertFailsWith<Exception> {
            provider.getPublicImage(odinId)
        }
        val countAfterFirst = requestCount

        // Server recovers; next call must re-hit the network.
        nextStatus = HttpStatusCode.OK
        val second = provider.getPublicImage(odinId)

        assertEquals(countAfterFirst + 1, requestCount, "500 must not be cached — second call must reach the network")
        assertNotNull(second)
        assertEquals(imageBytes.size, second.size)
    }

    @Test
    fun `clearCaches removes cached bytes so next call re-fetches from network`() = runTest {
        provider.getPublicImage(odinId)
        val countAfterPopulate = requestCount

        provider.clearCaches()

        provider.getPublicImage(odinId)
        assertTrue(
            requestCount > countAfterPopulate,
            "after clearCaches, next call must reach the network"
        )
    }

    /**
     * Mirror of DriveFileProviderCachedTest's per-cache-resilience assertion.
     * A single FileKache ctor failure must not hide the healthy sibling row.
     * Plants a regular file at the profile cache path so `createDirectories`
     * throws, exercising the tombstone + sentinel return.
     */
    @Test
    fun `getCacheStats returns sentinel for broken cache without hiding the healthy one`() = runTest {
        Files.write(Path.of(tempDir, "homebase-public-profiles-v2"), ByteArray(0))

        val stats = provider.getCacheStats()

        assertEquals(2, stats.size, "both rows must be returned even if one ctor failed")

        val profile = stats.single { it.id == "public_profiles" }
        val image = stats.single { it.id == "public_images" }

        assertEquals(
            CacheStats.UNAVAILABLE, profile.sizeBytes,
            "broken profile cache must be marked unavailable via the sentinel"
        )
        assertTrue(
            image.sizeBytes != CacheStats.UNAVAILABLE,
            "healthy image cache must NOT be marked unavailable (got sizeBytes=${image.sizeBytes})"
        )
    }
}

/** Kermit LogWriter that captures entries for test assertions. */
private class CollectingLogWriter : LogWriter() {
    data class Entry(val severity: Severity, val tag: String, val message: String, val throwable: Throwable?)

    val entries: MutableList<Entry> = mutableListOf()

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        entries += Entry(severity, tag, message, throwable)
    }

    fun hasError(tag: String, substring: String): Boolean =
        entries.any { it.tag == tag && it.severity == Severity.Error && it.message.contains(substring) }

    fun messages(tag: String): List<String> =
        entries.filter { it.tag == tag }.map { "[${it.severity}] ${it.message}" }
}
