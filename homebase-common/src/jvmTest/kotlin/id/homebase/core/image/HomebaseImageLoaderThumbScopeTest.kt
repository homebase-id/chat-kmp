package id.homebase.core.image

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.peer.PeerFileByGlobalTransitProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class HomebaseImageLoaderThumbScopeTest {

    private val thumbBytes = ByteArray(48) { it.toByte() }
    private val requests = AtomicInteger(0)
    private val requestArrived = CompletableDeferred<Unit>()
    private val releaseResponse = CompletableDeferred<Unit>()

    private val mockEngine = MockEngine {
        requests.incrementAndGet()
        requestArrived.complete(Unit)
        releaseResponse.await()
        respond(thumbBytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/webp"))
    }
    private val httpClient = HttpClient(mockEngine)
    private val credentialsManager = CredentialsManager()
    private var tempDir = ""

    private val fileOps = object : FileOperationsProvider {
        override fun getCacheDirectory() = tempDir
        override suspend fun readFileBytes(path: String): ByteArray = error("not used")
        override fun openFileInput(path: String): InputProvider = error("not used")
        override fun deleteTempFile(path: String) = false
        override fun getFileSize(path: String) = 0L
        override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used")
        override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used")
        override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used")
    }

    private lateinit var loader: HomebaseImageLoader

    private val avatar = HomebaseImageData(
        driveId = Uuid.parse("2612429d-0000-0000-0000-000000000001"),
        fileId = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
        payloadKey = "prfl_pic",
        keyHeader = KeyHeader.newRandom16(),
        lastModified = 1L,
    )
    private val size = ImageSize(116, 116)

    @BeforeTest
    fun setup() = runBlocking {
        tempDir = Files.createTempDirectory("hb-thumb-scope-test").toString()
        credentialsManager.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId("frodobaggins.me"),
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray(ByteArray(32) { 0x01 }),
            )
        )
        val driveCache = DriveFileProviderCached(httpClient, credentialsManager, fileOps)
        loader = HomebaseImageLoader(
            driveFileProvider = DriveFileProvider(httpClient, credentialsManager, driveCache),
            fileOperationsProvider = fileOps,
            peerFileProvider = PeerFileByGlobalTransitProvider(
                httpClient, credentialsManager, DriveFileHttpProvider(httpClient, credentialsManager), driveCache,
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        httpClient.close()
        File(tempDir).deleteRecursively()
    }

    @Test
    fun `a thumb fetch outlives the caller that started it and lands in the cache`() = runBlocking {
        withTimeout(10_000) {
            val row = launch(Dispatchers.Default) { loader.loadThumbnail(avatar, size) }
            requestArrived.await()
            row.cancelAndJoin()
            releaseResponse.complete(Unit)

            val next = loader.loadThumbnail(avatar, size)

            assertContentEquals(thumbBytes, next?.bytes)
            assertEquals(1, requests.get())
        }
    }

    @Test
    fun `concurrent requests for the same thumb share one download`() = runBlocking {
        withTimeout(10_000) {
            val callers = List(5) { async(Dispatchers.Default) { loader.loadThumbnail(avatar, size) } }
            requestArrived.await()
            releaseResponse.complete(Unit)

            callers.awaitAll().forEach { assertContentEquals(thumbBytes, it?.bytes) }
            assertEquals(1, requests.get())
        }
    }
}
