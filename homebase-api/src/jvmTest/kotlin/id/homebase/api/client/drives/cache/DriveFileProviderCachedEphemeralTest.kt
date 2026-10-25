package id.homebase.api.client.drives.cache

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesCbc
import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** A view-once payload is read over the network and never lands in a disk cache. */
class DriveFileProviderCachedEphemeralTest {

    private val driveId = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val fileId = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val key = "chat_web0"
    private val plain = ByteArray(300) { (it % 251).toByte() }
    private val aes = SecureByteArray(ByteArray(16) { 7 })
    private val iv = ByteArray(16) { 3 }
    private val keyHeader = KeyHeader(iv = iv, aesKey = aes)

    private var requests = 0
    private var status = HttpStatusCode.OK
    private lateinit var cipher: ByteArray
    private lateinit var tempDir: String
    private lateinit var cached: DriveFileProviderCached
    private lateinit var provider: DriveFileProvider
    private lateinit var http: HttpClient

    @BeforeTest
    fun setup() = runBlocking {
        cipher = AesCbc.encrypt(plain, aes, iv)
        tempDir = Files.createTempDirectory("hb-ephemeral-test").toString()
        val credentials = CredentialsManager().also {
            it.setActiveCredentials(
                ApiCredentials.create(OdinId("frodobaggins.me"), "t", SecureByteArray(ByteArray(32) { 1 })),
            )
        }
        http = HttpClient(MockEngine { _ ->
            requests++
            respond(
                content = if (status == HttpStatusCode.OK) cipher else ByteArray(0),
                status = status,
                headers = headersOf("payloadencrypted" to listOf("true")),
            )
        })
        cached = DriveFileProviderCached(http, credentials, object : FileOperationsProvider {
            override fun getCacheDirectory() = tempDir
            override fun openFileInput(path: String): InputProvider = error("unused")
            override suspend fun readFileBytes(path: String): ByteArray = error("unused")
            override fun deleteTempFile(path: String) = false
            override fun getFileSize(path: String) = 0L
            override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("unused")
            override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("unused")
            override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("unused")
        })
        provider = DriveFileProvider(http, credentials, cached)
    }

    @AfterTest
    fun tearDown() {
        http.close()
        runCatching {
            Files.walk(Path.of(tempDir)).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private suspend fun cachedBytes(): Long = cached.getCacheStats()
        .filter { it.id == "drive_payloads" || it.id == "hls_chunks" }
        .sumOf { it.sizeBytes }

    @Test
    fun anEphemeralFileSkipsTheDiskCachesForEveryReaderUntilEvicted() = runTest {
        provider.markPayloadEphemeral(fileId)

        // The video path: full-payload and range reads, decrypted through the cached provider.
        provider.getPayloadBytesDecrypted(driveId, fileId, key, keyHeader)
        provider.getPayloadBytesDecrypted(driveId, fileId, key, keyHeader)
        provider.getPayloadBytesEncrypted(driveId, fileId, key)
        assertEquals(3, requests)
        assertEquals(0L, cachedBytes())

        val before = requests
        provider.prefetchPayload(driveId, fileId, key, null)
        provider.prefetchPayloadChunk(driveId, fileId, key, 0L, 100L, null)
        assertEquals(before, requests, "a prefetch would only write the cache")

        provider.evictFile(driveId, fileId, key)
        provider.getPayloadBytesEncrypted(driveId, fileId, key)
        provider.getPayloadBytesEncrypted(driveId, fileId, key)
        assertEquals(before + 1, requests, "after the eviction the file is an ordinary cached one again")
    }

    @Test
    fun anEphemeralStreamIgnoresAnEntryCachedBeforeTheMark() = runTest {
        provider.getPayloadBytesEncrypted(driveId, fileId, key)
        provider.markPayloadEphemeral(fileId)
        val out = Path.of(tempDir, "streamed").toString()
        val writer = object : FileOperationsProvider {
            override fun getCacheDirectory() = tempDir
            override fun openFileInput(path: String): InputProvider = error("unused")
            override suspend fun readFileBytes(path: String): ByteArray = error("unused")
            override fun deleteTempFile(path: String) = false
            override fun getFileSize(path: String) = 0L
            override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("unused")
            override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("unused")
            override suspend fun writeStream(path: String, data: Flow<ByteArray>) {
                java.io.File(path).outputStream().use { o -> data.collect { o.write(it) } }
            }
        }

        assertEquals(true, provider.streamPayloadDecryptedToPath(driveId, fileId, key, keyHeader, out, writer))

        assertEquals(2, requests, "the stream goes to the network, not the earlier cache entry")
        assertContentEquals(plain, java.io.File(out).readBytes())
    }

    @Test
    fun evictingRemovesAnEntryWrittenBeforeTheFileWasMarked() = runTest {
        provider.getPayloadBytesEncrypted(driveId, fileId, key)
        assertEquals(1, requests)
        assertEquals(true, cachedBytes() > 0L)

        provider.evictFile(driveId, fileId, key)

        provider.getPayloadBytesEncrypted(driveId, fileId, key)
        assertEquals(2, requests, "the eviction dropped the cached entry")
    }

    @Test
    fun aFileStaysEphemeralUntilEveryHolderHasEvicted() = runTest {
        provider.markPayloadEphemeral(fileId)
        provider.markPayloadEphemeral(fileId)

        provider.evictFile(driveId, fileId, key)
        assertEquals(true, cached.isEphemeral(fileId), "a live holder remains")
        provider.getPayloadBytesDecrypted(driveId, fileId, key, keyHeader)
        assertEquals(0L, cachedBytes())

        provider.evictFile(driveId, fileId, key)
        assertEquals(false, cached.isEphemeral(fileId))

        provider.evictFile(driveId, fileId, key)
        provider.markPayloadEphemeral(fileId)
        assertEquals(true, cached.isEphemeral(fileId), "an unmatched evict must not leave a negative count")
    }
}
