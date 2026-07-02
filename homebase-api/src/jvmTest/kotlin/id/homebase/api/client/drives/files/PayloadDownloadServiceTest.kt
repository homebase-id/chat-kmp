package id.homebase.api.client.drives.files

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.PayloadSizePolicy
import id.homebase.api.client.PayloadTooLargeException
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The download facade (#845): RENDER intent is guarded byte-array reads;
 * EXPORT intent streams to a reserved path in an existing swept dir at any
 * size, bypassing the LRU. Runs the REAL provider stack (facade →
 * DriveFileProvider → DriveFileProviderCached → ktor MockEngine) so the
 * export path exercises genuine streamed decryption end to end.
 */
class PayloadDownloadServiceTest {

    private var nextStatus = HttpStatusCode.OK
    private var nextBody: ByteArray = ByteArray(0)
    private var nextHeaders: Headers = headersOf()

    private val mockEngine = MockEngine { _ ->
        respond(nextBody, nextStatus, nextHeaders)
    }
    private val httpClient = HttpClient(mockEngine)
    private val credentialsManager = CredentialsManager()
    private var tempDir: String = ""

    private lateinit var service: PayloadDownloadService

    private val driveId = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val fileId = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val key = "attachment"

    // Real-file-backed fake: reserve-path defaults come from the interface
    // (real FS under tempDir), writeStream lands on disk — the export contract.
    private val fileOps = object : FileOperationsProvider {
        override fun getCacheDirectory() = tempDir
        override fun openFileInput(path: String): InputProvider = error("not used")
        override suspend fun readFileBytes(path: String): ByteArray = File(path).readBytes()
        override fun deleteTempFile(path: String): Boolean = File(path).delete()
        override fun getFileSize(path: String): Long = File(path).length()
        override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used")
        override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used")
        override suspend fun writeStream(path: String, data: Flow<ByteArray>) {
            File(path).parentFile?.mkdirs()
            File(path).outputStream().buffered().use { out -> data.collect { out.write(it) } }
        }
    }

    @BeforeTest
    fun setup() = runBlocking {
        tempDir = Files.createTempDirectory("hb-download-test").toString()
        credentialsManager.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId("frodobaggins.me"),
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray(ByteArray(32) { 0x01 }),
            )
        )
        val driveCache = DriveFileProviderCached(httpClient, credentialsManager, fileOps)
        service = PayloadDownloadService(DriveFileProvider(httpClient, credentialsManager, driveCache), fileOps)

        nextStatus = HttpStatusCode.OK
        nextBody = ByteArray(0)
        nextHeaders = headersOf()
    }

    @AfterTest
    fun tearDown() {
        httpClient.close()
        runCatching {
            Files.walk(java.nio.file.Path.of(tempDir)).sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    private suspend fun encryptedFixture(size: Int): Pair<KeyHeader, ByteArray> {
        val keyHeader = KeyHeader.newRandom16()
        val plaintext = ByteArray(size).also { Random.nextBytes(it) }
        nextBody = keyHeader.encryptDataAes(plaintext)
        return keyHeader to plaintext
    }

    @Test
    fun `exportToTemp streams a decrypted copy into share_outbound`() = runTest {
        val (keyHeader, plaintext) = encryptedFixture(200_000)

        val path = service.exportToTemp(
            driveId, fileId, key, keyHeader,
            destination = ExportDestination.ShareOutbound(".zip"),
        )

        assertNotNull(path)
        assertTrue(path.contains("share_outbound"), "must land in the sequestered share dir: $path")
        assertTrue(path.endsWith(".zip"))
        assertContentEquals(plaintext, File(path).readBytes(), "streamed decrypt must be byte-exact")
    }

    @Test
    fun `exportToTemp routes UploadTemp and CacheRoot destinations to their dirs`() = runTest {
        val (keyHeader, plaintext) = encryptedFixture(4_096)

        val uploadTemp = service.exportToTemp(
            driveId, fileId, key, keyHeader, ExportDestination.UploadTemp("share_", ".pdf"))
        assertNotNull(uploadTemp)
        assertTrue(uploadTemp.contains("upload-temp"), "UploadTemp must land in upload-temp/: $uploadTemp")
        assertContentEquals(plaintext, File(uploadTemp).readBytes())

        val cacheRoot = service.exportToTemp(
            driveId, fileId, key, keyHeader, ExportDestination.CacheRoot("hbvid_res_", ".mp4"))
        assertNotNull(cacheRoot)
        assertEquals(tempDir, File(cacheRoot).parent, "CacheRoot must land at the cacheDir root")
        assertTrue(File(cacheRoot).name.startsWith("hbvid_res_"))
    }

    @Test
    fun `exportToTemp on 404 returns null and leaves no reserved file behind`() = runTest {
        nextStatus = HttpStatusCode.NotFound

        val path = service.exportToTemp(
            driveId, fileId, key, KeyHeader.newRandom16(),
            ExportDestination.ShareOutbound(".bin"),
        )

        assertNull(path)
        val shareDir = File(tempDir, "share_outbound")
        assertTrue(
            shareDir.listFiles().isNullOrEmpty(),
            "a failed export must not leave a reserved file: ${shareDir.listFiles()?.toList()}",
        )
    }

    @Test
    fun `exportToTemp forwards progress and settles at 1f`() = runTest {
        val (keyHeader, _) = encryptedFixture(300_000)
        // Content-Length drives the progress fraction on the network path.
        nextHeaders = headersOf(HttpHeaders.ContentLength, nextBody.size.toString())
        val seen = mutableListOf<Float>()

        service.exportToTemp(
            driveId, fileId, key, keyHeader,
            ExportDestination.CacheRoot("export_", ".bin"),
            onProgress = { seen.add(it) },
        )

        assertTrue(seen.isNotEmpty(), "progress must be reported")
        assertEquals(1f, seen.last(), "progress must settle at done")
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a }, "progress must be monotonic: $seen")
    }

    @Test
    fun `renderBytes refuses an export-sized payload with the typed error`() = runTest {
        // Real oversized body — MockEngine validates the Content-Length header.
        nextBody = ByteArray((PayloadSizePolicy.RENDER_LIMIT_BYTES + 1).toInt())
        nextHeaders = headersOf(HttpHeaders.ContentLength, (PayloadSizePolicy.RENDER_LIMIT_BYTES + 1).toString())

        assertFailsWith<PayloadTooLargeException> {
            service.renderBytes(driveId, fileId, key, KeyHeader.newRandom16())
        }
    }

    @Test
    fun `renderBytes returns null on 404`() = runTest {
        nextStatus = HttpStatusCode.NotFound
        assertNull(service.renderBytes(driveId, fileId, key, KeyHeader.newRandom16()))
    }
}
