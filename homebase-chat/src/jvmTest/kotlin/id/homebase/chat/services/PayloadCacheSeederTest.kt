package id.homebase.chat.services
import id.homebase.upload.PayloadCacheSeeder
import id.homebase.upload.PayloadBundle

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Verifies that [PayloadCacheSeeder] writes every payload + thumbnail in a bundle
 * into the encrypted disk cache under the given fileId — keyed by the thumbnail's
 * native size — so a later read is served locally with no network. Best-effort:
 * one unreadable payload doesn't abort the rest.
 */
class PayloadCacheSeederTest {

    private val driveId = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val fileId = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    // MockEngine throws on any request — proves seeded reads never touch the network.
    private val mockEngine = MockEngine { throw IOException("network must not be reached for a seeded read") }
    private val httpClient = HttpClient(mockEngine)
    private val credentialsManager = CredentialsManager()
    private var tempDir: String = ""

    // Returns bytes for a known path; throws for the "missing" path to exercise best-effort.
    private val fileBytes = mutableMapOf<String, ByteArray>()
    // Overrides what getFileSize reports for a path (defaults to the byte length) —
    // lets the cap test claim a huge size without allocating it.
    private val fileSizes = mutableMapOf<String, Long>()
    // Every readFileBytes call — the cap test asserts the capped payload was never read.
    private val readPaths = mutableListOf<String>()
    private val fileOps = object : FileOperationsProvider {
        override fun getCacheDirectory() = tempDir
        override suspend fun readFileBytes(path: String): ByteArray {
            readPaths += path
            return fileBytes[path] ?: throw IOException("no bytes for $path")
        }
        override fun openFileInput(path: String): InputProvider = error("not used")
        override fun deleteTempFile(path: String) = false
        override fun getFileSize(path: String) =
            fileSizes[path] ?: fileBytes[path]?.size?.toLong() ?: 0L
        override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used")
        override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used")
        override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used")
    }

    private lateinit var driveCache: DriveFileProviderCached
    private lateinit var seeder: PayloadCacheSeeder

    @BeforeTest
    fun setup() = runBlocking {
        tempDir = Files.createTempDirectory("hb-seeder-test").toString()
        credentialsManager.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId("frodobaggins.me"),
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray(ByteArray(32) { 0x01 }),
            )
        )
        driveCache = DriveFileProviderCached(httpClient, credentialsManager, fileOps)
        // The seeder writes via DriveFileProvider; reads below go straight to the
        // cache (where the raw getters live) to assert the bytes landed.
        val provider = DriveFileProvider(httpClient, credentialsManager, driveCache)
        seeder = PayloadCacheSeeder(provider, fileOps)
    }

    @AfterTest
    fun tearDown() {
        httpClient.close()
        runCatching {
            Files.walk(java.nio.file.Path.of(tempDir)).sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `seeds payload and native-size thumbnails so reads hit the cache with no network`() = runTest {
        val payloadBytes = ByteArray(64) { it.toByte() }
        fileBytes["/tmp/enc-payload"] = payloadBytes
        val thumb320 = ByteArray(40) { (it * 2).toByte() }
        val thumb640 = ByteArray(50) { (it * 3).toByte() }

        val bundle = PayloadBundle(
            payloads = listOf(
                PayloadFile(key = "photo", filePath = "/tmp/enc-payload", contentType = "image/jpeg"),
            ),
            thumbnails = listOf(
                ThumbnailFile(pixelWidth = 320, pixelHeight = 240, thumbnailBytes = thumb320, key = "photo"),
                ThumbnailFile(pixelWidth = 640, pixelHeight = 480, thumbnailBytes = thumb640, key = "photo"),
            ),
            previewThumbs = emptyList(),
        )

        seeder.seed(driveId, fileId, bundle)

        // Payload: served from the seed (raw, encrypted-flagged) with no network.
        val payload = driveCache.getPayloadBytesRaw(driveId, fileId, "photo")
        assertEquals(200, payload.status)
        assertContentEquals(payloadBytes, payload.bytes)
        assertEquals("true", payload.headers["payloadencrypted"])

        // Thumbs: each keyed by its NATIVE size — both hit, no network.
        val t320 = driveCache.getThumbBytesRaw(driveId, fileId, "photo", 320, 240)
        assertContentEquals(thumb320, t320.bytes)
        val t640 = driveCache.getThumbBytesRaw(driveId, fileId, "photo", 640, 480)
        assertContentEquals(thumb640, t640.bytes)
    }

    @Test
    fun `is best-effort — an unreadable payload is skipped, the rest still seeded`() = runTest {
        val goodBytes = ByteArray(16) { 0x42 }
        fileBytes["/tmp/good"] = goodBytes
        // "/tmp/bad" is intentionally absent → readFileBytes throws for it.

        val bundle = PayloadBundle(
            payloads = listOf(
                PayloadFile(key = "bad", filePath = "/tmp/bad", contentType = "image/jpeg"),
                PayloadFile(key = "good", filePath = "/tmp/good", contentType = "image/jpeg"),
            ),
            thumbnails = listOf(
                ThumbnailFile(pixelWidth = 320, pixelHeight = 320, thumbnailBytes = ByteArray(8) { 7 }, key = "good"),
            ),
            previewThumbs = emptyList(),
        )

        // Must not throw despite the bad payload.
        seeder.seed(driveId, fileId, bundle)

        val good = driveCache.getPayloadBytesRaw(driveId, fileId, "good")
        assertContentEquals(goodBytes, good.bytes)
        val thumb = driveCache.getThumbBytesRaw(driveId, fileId, "good", 320, 320)
        assertContentEquals(ByteArray(8) { 7 }, thumb.bytes)
    }

    /**
     * #947: a payload above [PayloadCacheSeeder.MAX_SEED_PAYLOAD_BYTES] must not be
     * read at all (readFileBytes buffers the whole payload in RAM — a full-video-size
     * allocation per send) and must not land in the cache; its thumbnails are the
     * critical seed and still land. Small payloads in the same bundle are unaffected.
     */
    @Test
    fun `skips payload seeding above the size cap but still seeds thumbnails`() = runTest {
        val bigPath = "/tmp/huge-video"
        fileBytes[bigPath] = ByteArray(4) { 9 }              // present on disk...
        fileSizes[bigPath] = PayloadCacheSeeder.MAX_SEED_PAYLOAD_BYTES + 1  // ...but reports as huge
        val smallBytes = ByteArray(16) { 0x21 }
        fileBytes["/tmp/small"] = smallBytes
        val videoThumb = ByteArray(12) { 5 }

        val bundle = PayloadBundle(
            payloads = listOf(
                PayloadFile(key = "video", filePath = bigPath, contentType = "video/mp2t"),
                PayloadFile(key = "photo", filePath = "/tmp/small", contentType = "image/jpeg"),
            ),
            thumbnails = listOf(
                ThumbnailFile(pixelWidth = 320, pixelHeight = 180, thumbnailBytes = videoThumb, key = "video"),
            ),
            previewThumbs = emptyList(),
        )

        seeder.seed(driveId, fileId, bundle)

        assertTrue(bigPath !in readPaths, "capped payload must never be readFileBytes'd")
        // Not seeded → the read escapes the cache and hits the throwing MockEngine.
        assertFails("capped payload must not be served from the cache") {
            driveCache.getPayloadBytesRaw(driveId, fileId, "video")
        }
        // The video's thumbnail and the sibling small payload are seeded as before.
        val thumb = driveCache.getThumbBytesRaw(driveId, fileId, "video", 320, 180)
        assertContentEquals(videoThumb, thumb.bytes)
        val small = driveCache.getPayloadBytesRaw(driveId, fileId, "photo")
        assertContentEquals(smallBytes, small.bytes)
    }
}
