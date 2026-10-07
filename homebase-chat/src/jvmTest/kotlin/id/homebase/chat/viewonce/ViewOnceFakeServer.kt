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
import kotlin.uuid.Uuid

/** A drive that serves one AES-encrypted view-once payload, over the real provider and cache stack. */
internal class ViewOnceFakeServer(
    val chatDriveId: Uuid = Uuid.parse("9ff813af-f2d6-1e2f-9b9d-b189e72d1a11"),
    val fileId: Uuid = Uuid.random(),
) {
    val plainImage: ByteArray = ByteArrayOutputStream().also { out ->
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 16) for (y in 0 until 16) image.setRGB(x, y, if ((x + y) % 2 == 0) 0xFF8800 else 0x2244AA)
        ImageIO.write(image, "png", out)
    }.toByteArray()

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
        loader = ViewOncePayloadLoader(provider) { drive, file -> evictedImages += drive to file }
        return this
    }

    suspend fun cachedBytes(): Long = cached.getCacheStats()
        .filter { it.id == "drive_payloads" || it.id == "hls_chunks" }
        .sumOf { it.sizeBytes }

    fun viewer(messageId: Uuid = Uuid.random(), kind: String = ViewOnceDescriptor.KIND_IMAGE) =
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
        )
}
