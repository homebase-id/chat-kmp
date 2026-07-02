package id.homebase.api.client.drives.cache

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.cache.CacheStats
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import id.homebase.api.client.PayloadSizePolicy
import id.homebase.api.client.PayloadTooLargeException
import id.homebase.api.client.drives.files.PayloadOperationOptions
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
    private var nextBody: ByteArray = ByteArray(0)
    private var nextHeaders: Headers = headersOf()
    // When set, the body is served as a raw channel (NO Content-Length header) —
    // exercises the chunked-transfer fallback of the requestBytes size guard.
    private var nextChannelBody: ByteArray? = null

    private val mockEngine = MockEngine { _ ->
        requestCount++
        nextException?.let { e -> throw e }
        nextChannelBody?.let { body ->
            return@MockEngine respond(ByteReadChannel(body), nextStatus, nextHeaders)
        }
        respond(nextBody, nextStatus, nextHeaders)
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
                override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used in tests")
                override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used in tests")
            }
        )

        requestCount = 0
        nextException = null
        nextStatus = HttpStatusCode.OK
        nextBody = ByteArray(0)
        nextHeaders = headersOf()
        nextChannelBody = null

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
    fun `poisoned cache entry with non-2xx status is visible in the decrypt failure log`() = runTest {
        // Populate the cache with a normal 200 response, then rewrite the on-disk
        // data file with a valid-format ByteApiResponse whose status is 500.
        // Such entries should not exist post-61ebe154 (the write-guard blocks them),
        // but pre-fix code paths could have produced them — so a user upgrading
        // past the fix might still carry one on disk. This test documents the
        // failure mode and pins the diagnostic log to the right context.
        val thumbDir = Path.of(tempDir, "homebase-thumbs-v2")
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
        val thumbDir = Path.of(tempDir, "homebase-thumbs-v2")
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
     * Regression for real-device symptom where a single FileKache ctor NPE
     * on the thumb cache was causing BOTH drive rows to disappear from the
     * Storage screen (the ViewModel's outer runCatching substituted
     * emptyList for the whole getCacheStats result). Per-cache try/catch
     * must return a sentinel row for the broken cache + a real row for the
     * healthy one.
     *
     * Forces the thumb ctor to fail by planting a regular file at the path
     * the FileKache would otherwise create as a directory — createDirectories
     * then throws IOException, caught by thumbDiskKache's try/catch and
     * tombstoned.
     */
    @Test
    fun `getCacheStats returns sentinel for broken cache without hiding the healthy one`() = runTest {
        // Pre-fail the thumb cache: create homebase-thumbs as a FILE, which
        // makes the subsequent FileKache ctor blow up on createDirectories.
        Files.write(Path.of(tempDir, "homebase-thumbs-v2"), ByteArray(0))

        val stats = provider.getCacheStats()

        assertEquals(3, stats.size, "all rows must be returned even if one ctor failed")

        val payload = stats.single { it.id == "drive_payloads" }
        val thumb = stats.single { it.id == "drive_thumbnails" }
        val chunks = stats.single { it.id == "hls_chunks" }

        assertTrue(
            payload.sizeBytes != CacheStats.UNAVAILABLE,
            "healthy payload cache must NOT be marked unavailable (got sizeBytes=${payload.sizeBytes})"
        )
        assertEquals(
            CacheStats.UNAVAILABLE, thumb.sizeBytes,
            "broken thumb cache must be marked unavailable via the sentinel"
        )
        assertTrue(
            chunks.sizeBytes != CacheStats.UNAVAILABLE,
            "healthy chunk cache must NOT be marked unavailable (got sizeBytes=${chunks.sizeBytes})"
        )
    }

    /**
     * Seed-on-send round-trip: bytes written via [DriveFileProviderCached.cachePayloadBytesEncrypted]
     * must be served by the read path with NO network — this is what makes a
     * never-sent (failed) message's media retrievable for retry. The mock
     * engine throws on any request to prove the network is never touched.
     */
    // ==================== #845 PR2: dedicated HLS chunk cache ====================

    private fun statsSize(stats: List<CacheStats>, id: String): Long =
        stats.single { it.id == id }.sizeBytes

    @Test
    fun `range reads land in the chunk cache and never pollute the payload LRU`() = runTest {
        nextBody = ByteArray(4096) { 3 }

        provider.getPayloadBytesRaw(
            driveId, fileId, key,
            options = PayloadOperationOptions(chunkStart = 0L, chunkLength = 4096L),
        )

        val stats = provider.getCacheStats()
        assertTrue(statsSize(stats, "hls_chunks") > 0L, "chunk must land in the chunk cache: $stats")
        assertEquals(0L, statsSize(stats, "drive_payloads"), "the payload LRU must contain NO range entries: $stats")

        // Second identical range read → served from the chunk cache, no new fetch.
        val before = requestCount
        val again = provider.getPayloadBytesRaw(
            driveId, fileId, key,
            options = PayloadOperationOptions(chunkStart = 0L, chunkLength = 4096L),
        )
        assertEquals(before, requestCount, "warm chunk must be served without the network")
        assertContentEquals(nextBody, again.bytes)
    }

    /**
     * OWNER-REQUIRED (#845): the seg-0 PRELOADER and playback range reads must
     * converge on the same chunk cache. The preloader warms via
     * `DriveFileProvider.prefetchPayloadChunk`; iOS's LocalVideoServer reads the
     * same range via `getPayloadBytesEncryptedChunk` — two different entry
     * points, ONE network fetch, and zero range entries in the payload LRU.
     * (If routing were per-caller, prefetch would write the payload LRU while
     * playback reads the chunk cache: a guaranteed seg-0 double-download plus
     * pollution.)
     */
    @Test
    fun `prefetched seg0 is served to playback from the chunk cache with no second fetch`() = runTest {
        nextBody = ByteArray(2048) { 9 }
        val fileProvider = id.homebase.api.client.drives.files.DriveFileProvider(
            httpClient, credentialsManager, provider,
        )

        // 1) Preloader shape: VideoPreloader.preloadFirstHlsSegment → prefetchPayloadChunk.
        fileProvider.prefetchPayloadChunk(driveId, fileId, key, chunkStart = 0L, chunkLength = 2048L)
        assertEquals(1, requestCount)

        // 2) Playback shape: iOS LocalVideoServer → getPayloadBytesEncryptedChunk, same range.
        val bytes = fileProvider.getPayloadBytesEncryptedChunk(driveId, fileId, key, chunkStart = 0L, chunkLength = 2048L)

        assertEquals(1, requestCount, "playback must be served from the prefetch-warmed chunk cache")
        assertContentEquals(nextBody, assertNotNull(bytes))
        assertEquals(0L, statsSize(provider.getCacheStats(), "drive_payloads"),
            "no range entry may leak into the payload LRU")
    }

    @Test
    fun `full reads still land in the payload LRU, not the chunk cache`() = runTest {
        nextBody = ByteArray(1024) { 5 }

        provider.getPayloadBytesRaw(driveId, fileId, key)

        val stats = provider.getCacheStats()
        assertTrue(statsSize(stats, "drive_payloads") > 0L, "full read must land in the payload LRU: $stats")
        assertEquals(0L, statsSize(stats, "hls_chunks"), "full read must not leak into the chunk cache: $stats")
    }

    @Test
    fun `clearCaches empties the chunk cache and getCacheStats reports its 100 MB cap`() = runTest {
        nextBody = ByteArray(512) { 1 }
        provider.getPayloadBytesRaw(
            driveId, fileId, key,
            options = PayloadOperationOptions(chunkStart = 0L, chunkLength = 512L),
        )
        assertTrue(statsSize(provider.getCacheStats(), "hls_chunks") > 0L)
        assertEquals(100L * 1024 * 1024, provider.getCacheStats().single { it.id == "hls_chunks" }.maxBytes)

        provider.clearCaches()

        assertEquals(0L, statsSize(provider.getCacheStats(), "hls_chunks"))
        val before = requestCount
        provider.getPayloadBytesRaw(
            driveId, fileId, key,
            options = PayloadOperationOptions(chunkStart = 0L, chunkLength = 512L),
        )
        assertEquals(before + 1, requestCount, "cleared chunk must be re-fetched")
    }

    @Test
    fun `rekey leaves chunk entries alone`() = runTest {
        nextBody = ByteArray(256) { 2 }
        provider.getPayloadBytesRaw(
            driveId, fileId, key,
            options = PayloadOperationOptions(chunkStart = 0L, chunkLength = 256L),
        )
        val chunkBytesBefore = statsSize(provider.getCacheStats(), "hls_chunks")

        provider.rekeyCachedFile(
            driveId, fileId, Uuid.parse("cccccccc-cccc-cccc-cccc-cccccccccccc"),
            payloads = listOf(PayloadDescriptor(key = key, contentType = "video/mp2t")),
        )

        assertEquals(
            chunkBytesBefore, statsSize(provider.getCacheStats(), "hls_chunks"),
            "chunk entries are intentionally not rekeyed — they only exist post-sync",
        )
    }

    // ==================== #845: render/export size guard ====================

    @Test
    fun `full read over the render limit is refused via Content-Length before the body is read`() = runTest {
        // MockEngine validates the header against the body, so the oversized body is
        // real — the assertion that matters is the guard tripping on the HEADER
        // (sizeBytes == the advertised length, not -1 from the counting fallback).
        nextBody = ByteArray((PayloadSizePolicy.RENDER_LIMIT_BYTES + 1).toInt())
        nextHeaders = headersOf(HttpHeaders.ContentLength, (PayloadSizePolicy.RENDER_LIMIT_BYTES + 1).toString())

        val e = assertFailsWith<PayloadTooLargeException> {
            provider.getPayloadBytesRaw(driveId, fileId, key)
        }
        assertEquals(PayloadSizePolicy.RENDER_LIMIT_BYTES + 1, e.sizeBytes)
        assertEquals(PayloadSizePolicy.RENDER_LIMIT_BYTES, e.limitBytes)
        assertEquals(1, requestCount)

        // Not cached, not 404-poisoned: a fixed server (normal size) is fetched again.
        nextHeaders = headersOf()
        nextBody = ByteArray(8) { 1 }
        val second = provider.getPayloadBytesRaw(driveId, fileId, key)
        assertEquals(2, requestCount, "a refused read must not poison the notFound cache")
        assertContentEquals(nextBody, second.bytes)
    }

    @Test
    fun `chunked response without Content-Length is aborted once the running count passes the limit`() = runTest {
        // Channel body → MockEngine sends no Content-Length → the guard's counting
        // fallback must catch it mid-read.
        nextChannelBody = ByteArray((PayloadSizePolicy.RENDER_LIMIT_BYTES + 1024).toInt())

        val e = assertFailsWith<PayloadTooLargeException> {
            provider.getPayloadBytesRaw(driveId, fileId, key)
        }
        assertEquals(-1, e.sizeBytes, "no Content-Length → size unknown at refusal time")
    }

    @Test
    fun `range reads are exempt from the size guard even when the payload total is huge`() = runTest {
        // A range response legitimately advertises a large total via headers on some
        // servers; the guard must not applied to chunk-shaped requests at all.
        nextHeaders = headersOf(HttpHeaders.ContentLength, "4096")
        nextBody = ByteArray(4096) { 7 }

        val result = provider.getPayloadBytesRaw(
            driveId, fileId, key,
            options = PayloadOperationOptions(chunkStart = 0L, chunkLength = 4096L),
        )
        assertEquals(200, result.status)
        assertEquals(4096, result.bytes.size)
    }

    @Test
    fun `oversized seed is refused by the cache-admission fence and nothing is written`() = runTest {
        nextException = IOException("network must not be reached")
        val oversized = ByteArray((PayloadSizePolicy.RENDER_LIMIT_BYTES + 1).toInt())

        provider.cachePayloadBytesEncrypted(driveId, fileId, key, oversized, "application/zip")

        assertTrue(
            logCollector.entries.any { it.message.contains("cache-admit REFUSED") },
            "the refusal must be observable in the log",
        )
        // The entry must not be served from cache — the read goes to the (throwing) network.
        assertFailsWith<Exception> { provider.getPayloadBytesRaw(driveId, fileId, key) }
    }

    @Test
    fun `seeded payload bytes are served from cache without touching the network`() = runTest {
        nextException = IOException("network must not be reached for a seeded payload")
        val bytes = ByteArray(64) { it.toByte() }

        provider.cachePayloadBytesEncrypted(driveId, fileId, key, bytes, "image/jpeg")
        val result = provider.getPayloadBytesRaw(driveId, fileId, key)

        assertEquals(0, requestCount, "seeded read must not hit the network")
        assertEquals(200, result.status)
        assertContentEquals(bytes, result.bytes)
        assertEquals("image/jpeg", result.contentType)
        assertEquals(
            "true", result.headers["payloadencrypted"],
            "the encrypted bit must round-trip — decrypt-on-read depends on it"
        )
    }

    @Test
    fun `seeded thumb bytes are served from cache without touching the network`() = runTest {
        nextException = IOException("network must not be reached for a seeded thumb")
        val bytes = ByteArray(32) { (it * 3).toByte() }

        provider.cacheThumbBytesEncrypted(driveId, fileId, key, 100, 100, bytes, "image/webp")
        val result = provider.getThumbBytesRaw(driveId, fileId, key, 100, 100)

        assertEquals(0, requestCount, "seeded read must not hit the network")
        assertEquals(200, result.status)
        assertContentEquals(bytes, result.bytes)
        assertEquals("true", result.headers["payloadencrypted"])
    }

    /**
     * The finalizing-blur fix at the cache layer. The sender seeds thumbs under
     * their NATIVE generated sizes; the thumb cache key embeds width:height, so:
     *   - a read keyed by the native size (what loadThumbnail does after snapping
     *     via selectThumbSize) hits the seed with NO network — the bubble shows a
     *     sharp thumbnail through "finalizing", and
     *   - a read keyed by an arbitrary measured size (the old behaviour) MISSES the
     *     native-keyed seed and falls through to the network (which 404s while the
     *     optimistic fileId is not yet on the server) — which is why the bubble used
     *     to drop back to the blurry 20px embedded preview.
     * lastModified is null on both sides pre-sync, so only width:height differs.
     */
    @Test
    fun `seeded native-size thumb is hit by a native-size read but missed by a measured-size read`() = runTest {
        val nativeW = 320
        val nativeH = 240
        val bytes = ByteArray(48) { (it * 5).toByte() }
        provider.cacheThumbBytesEncrypted(driveId, fileId, key, nativeW, nativeH, bytes, "image/webp")

        // Native-keyed read: served by the seed, network must not be touched.
        nextException = IOException("native-size read must be served from the seed, not the network")
        val hit = provider.getThumbBytesRaw(driveId, fileId, key, nativeW, nativeH)
        assertEquals(0, requestCount, "native-keyed read must be served by the seed")
        assertEquals(200, hit.status)
        assertContentEquals(bytes, hit.bytes)

        // Measured-size read (e.g. a 712x950 layout): misses the native-keyed seed
        // and reaches the network — demonstrating the pre-fix behaviour.
        nextException = null
        nextStatus = HttpStatusCode.OK
        val miss = provider.getThumbBytesRaw(driveId, fileId, key, 712, 950)
        assertTrue(
            requestCount > 0,
            "measured-size read must miss the native-keyed seed and hit the network"
        )
        assertEquals(200, miss.status)
    }

    /**
     * A pre-send 404 lookup (e.g. a bubble probing a payload before the seed
     * landed) must not shadow the seeded entry: seeding clears the in-memory
     * notFound marker for the key.
     */
    @Test
    fun `seeding clears a prior 404 marker for the key`() = runTest {
        nextStatus = HttpStatusCode.NotFound
        assertFailsWith<NotFoundException> {
            provider.getPayloadBytesRaw(driveId, fileId, key)
        }
        val countAfter404 = requestCount

        val bytes = ByteArray(16) { 0x42 }
        provider.cachePayloadBytesEncrypted(driveId, fileId, key, bytes, "image/jpeg")

        nextException = IOException("network must not be reached after seeding")
        val result = provider.getPayloadBytesRaw(driveId, fileId, key)

        assertEquals(countAfter404, requestCount, "read after seeding must not hit the network")
        assertEquals(200, result.status, "the 404 marker must not shadow the seeded bytes")
        assertContentEquals(bytes, result.bytes)
    }

    // ---- rekeyCachedFile: optimistic→server fileId move at sync-back ----

    private val newFileId = Uuid.parse("cccccccc-cccc-cccc-cccc-cccccccccccc")
    private val serverLastModified = 1_780_000_000_000L

    private fun rekeyDescriptors() = listOf(
        PayloadDescriptor(
            key = key,
            contentType = "image/jpeg",
            lastModified = serverLastModified,
            thumbnails = listOf(
                ThumbnailDescriptor(
                    pixelWidth = 100,
                    pixelHeight = 100,
                    contentType = "image/webp",
                ),
            ),
        ),
    )

    @Test
    fun `rekey moves seeded payload and thumb to the new fileId without touching the network`() = runTest {
        val payloadBytes = ByteArray(64) { it.toByte() }
        val thumbBytes = ByteArray(32) { (it * 3).toByte() }
        provider.cachePayloadBytesEncrypted(driveId, fileId, key, payloadBytes, "image/jpeg")
        provider.cacheThumbBytesEncrypted(driveId, fileId, key, 100, 100, thumbBytes, "image/webp")

        nextException = IOException("network must not be reached for a rekeyed entry")
        provider.rekeyCachedFile(driveId, fileId, newFileId, rekeyDescriptors())

        val payload = provider.getPayloadBytesRaw(driveId, newFileId, key)
        assertContentEquals(payloadBytes, payload.bytes)
        assertEquals("true", payload.headers["payloadencrypted"], "the encrypted bit must survive the copy")
        // The thumb must land under the SERVER's lastModified — that's the key
        // post-sync readers use (the seed was written under null).
        val thumb = provider.getThumbBytesRaw(driveId, newFileId, key, 100, 100, serverLastModified)
        assertContentEquals(thumbBytes, thumb.bytes)
        assertEquals(0, requestCount, "rekeyed reads must not hit the network")
    }

    @Test
    fun `rekey with no seeded source is a silent no-op`() = runTest {
        provider.rekeyCachedFile(driveId, fileId, newFileId, rekeyDescriptors())

        // Nothing cached under the new id — the read goes to the network.
        nextStatus = HttpStatusCode.OK
        provider.getPayloadBytesRaw(driveId, newFileId, key)
        assertEquals(1, requestCount)
    }

    @Test
    fun `rekey removes the old entries`() = runTest {
        provider.cachePayloadBytesEncrypted(driveId, fileId, key, ByteArray(16) { 1 }, "image/jpeg")
        provider.rekeyCachedFile(driveId, fileId, newFileId, rekeyDescriptors())

        // The old key's LRU budget is freed — a read under the old fileId must
        // fall through to the network.
        nextStatus = HttpStatusCode.OK
        provider.getPayloadBytesRaw(driveId, fileId, key)
        assertEquals(1, requestCount, "old entry must be removed by the rekey")
    }

    @Test
    fun `rekey clears a cached 404 for the new key`() = runTest {
        // A racing probe 404-caches the NEW payload key before the rekey lands.
        nextStatus = HttpStatusCode.NotFound
        assertFailsWith<NotFoundException> {
            provider.getPayloadBytesRaw(driveId, newFileId, key)
        }
        val countAfter404 = requestCount

        val bytes = ByteArray(16) { 0x42 }
        provider.cachePayloadBytesEncrypted(driveId, fileId, key, bytes, "image/jpeg")
        provider.rekeyCachedFile(driveId, fileId, newFileId, rekeyDescriptors())

        nextException = IOException("network must not be reached after rekey")
        val result = provider.getPayloadBytesRaw(driveId, newFileId, key)
        assertEquals(countAfter404, requestCount, "read after rekey must not hit the network")
        assertContentEquals(bytes, result.bytes, "the 404 marker must not shadow the moved entry")
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

/** Snapshot every regular file under [dir] (recursively), skipping the disk-cache journal. */
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
