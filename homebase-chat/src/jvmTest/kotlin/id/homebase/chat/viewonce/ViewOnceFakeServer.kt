package id.homebase.chat.viewonce

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesCbc
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.AppCacheDirs
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.peer.PeerFileByGlobalTransitProvider
import id.homebase.core.image.HomebaseImageLoader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.ImageLoader
import coil3.PlatformContext
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import id.homebase.chat.conversationlist.FullScreenOverlay
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlin.uuid.Uuid

// The zoomable asserts Swing's EDT, as the desktop app runs Compose there; a test that gestures on it must too.
internal fun onEdt(block: () -> Unit) {
    var failure: Throwable? = null
    javax.swing.SwingUtilities.invokeAndWait { failure = runCatching(block).exceptionOrNull() }
    failure?.let { throw it }
}

/** A drive that serves one AES-encrypted view-once payload, over the real provider and cache stack. */
internal class ViewOnceFakeServer(
    val chatDriveId: Uuid = Uuid.parse("9ff813af-f2d6-1e2f-9b9d-b189e72d1a11"),
    val fileId: Uuid = Uuid.random(),
    val plainImage: ByteArray = checkerboardPng(),
) {

    private val aes = SecureByteArray(ByteArray(16) { 9 })
    private val iv = ByteArray(16) { 5 }
    val keyHeader = KeyHeader(iv = iv, aesKey = aes)

    var requests = 0
    var requestedPaths = mutableListOf<String>()
    var status: HttpStatusCode = HttpStatusCode.OK
    val tempDir: String = Files.createTempDirectory("vo-server").toString()

    lateinit var cached: DriveFileProviderCached
    lateinit var provider: DriveFileProvider
    var evictedImages = mutableListOf<Pair<Uuid, Uuid>>()
    lateinit var loader: ViewOncePayloadLoader
    lateinit var homebaseImageLoader: HomebaseImageLoader
    val coil: ImageLoader = ImageLoader.Builder(PlatformContext.INSTANCE).build()
    val viewOnceTempDir: String get() = AppCacheDirs.scratchDir(tempDir, AppCacheDirs.VIEW_ONCE)
    val evictedTemps = mutableListOf<String>()

    fun tempFiles(): List<java.io.File> = java.io.File(viewOnceTempDir).listFiles()?.toList().orEmpty()

    fun coilKeysFor(path: String): List<String> = coil.memoryCache?.keys?.map { it.key }?.filter { path in it }.orEmpty()

    @Composable
    fun Provide(content: @Composable () -> Unit) {
        val koin = remember {
            koinApplication { modules(module { single { coil }; single { homebaseImageLoader } }) }
        }
        KoinIsolatedContext(koin, content = content)
    }

    val fileOps = object : FileOperationsProvider {
        override fun getCacheDirectory() = tempDir
        override fun openFileInput(path: String): InputProvider = error("unused")
        override suspend fun readFileBytes(path: String): ByteArray = error("unused")
        override fun deleteTempFile(path: String) = java.io.File(path).let { it.delete() || !it.exists() }
        override fun getFileSize(path: String) = 0L
        override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("unused")
        override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("unused")
        override suspend fun writeStream(path: String, data: Flow<ByteArray>) {
            val file = java.io.File(path).also { it.parentFile.mkdirs() }
            file.outputStream().use { out -> data.collect { out.write(it) } }
        }
    }

    suspend fun start(): ViewOnceFakeServer {
        val cipher = AesCbc.encrypt(plainImage, aes, iv)
        val credentials = CredentialsManager().also {
            it.setActiveCredentials(
                ApiCredentials.create(OdinId("owner.test"), "t", SecureByteArray(ByteArray(32) { 1 })),
            )
        }
        val http = HttpClient(MockEngine { request ->
            requests++
            requestedPaths += request.url.encodedPath
            respond(
                content = if (status == HttpStatusCode.OK) cipher else ByteArray(0),
                status = status,
                headers = headersOf("payloadencrypted" to listOf("true")),
            )
        })
        cached = DriveFileProviderCached(http, credentials, fileOps)
        provider = DriveFileProvider(http, credentials, cached)
        homebaseImageLoader = HomebaseImageLoader(
            driveFileProvider = provider,
            fileOperationsProvider = fileOps,
            peerFileProvider = PeerFileByGlobalTransitProvider(http, credentials, DriveFileHttpProvider(http, credentials), cached),
        )
        loader = ViewOncePayloadLoader(
            provider,
            fileOps,
            tempDir = { viewOnceTempDir },
            canView = { true },
            evictLocalImage = { path ->
                coil.evictMemoryFor(path)
                evictedTemps += path
            },
        ) { drive, file -> evictedImages += drive to file }
        return this
    }

    suspend fun cachedBytes(): Long = cached.getCacheStats()
        .filter { it.id == "drive_payloads" || it.id == "hls_chunks" }
        .sumOf { it.sizeBytes }

    fun viewer(
        messageId: Uuid = Uuid.random(),
        kind: String = ViewOnceDescriptor.KIND_IMAGE,
        caption: String? = null,
        senderName: String? = null,
        sentAt: Instant? = null,
    ) =
        FullScreenOverlay.ViewOnceViewer(
            messageId = messageId,
            conversationId = Uuid.random(),
            fileId = fileId,
            payload = PayloadDescriptor(
                key = VIEW_ONCE_PAYLOAD_KEY,
                contentType = "image/png",
                iv = Base64.encode(iv),
            ),
            keyHeader = keyHeader,
            kind = kind,
            caption = caption,
            senderName = senderName,
            sentAt = sentAt,
        )
}

private fun checkerboardPng(): ByteArray = ByteArrayOutputStream().also { out ->
    val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until 16) for (y in 0 until 16) image.setRGB(x, y, if ((x + y) % 2 == 0) 0xFF8800 else 0x2244AA)
    ImageIO.write(image, "png", out)
}.toByteArray()
