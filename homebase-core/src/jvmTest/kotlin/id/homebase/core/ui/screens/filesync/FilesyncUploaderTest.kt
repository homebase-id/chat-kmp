package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.ClientException
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.upload.CreateFileResult
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.serialization.Base64Bytes
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.filesync.FilesyncProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The create rules from filesync's drive/mod.rs: BLAKE3 hashes, 4105 is success, hash rejections re-encrypt once. */
class FilesyncUploaderTest {

    private val credentials = CredentialsManager()
    private lateinit var tempDir: String
    private lateinit var source: String
    private val bodies = mutableListOf<String>()
    private var responses: MutableList<suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData> = mutableListOf()

    private val engine = MockEngine { request ->
        bodies += request.body.toByteArray().decodeToString(throwOnInvalidSequence = false)
        responses.removeFirst()(this, request)
    }

    private lateinit var uploader: FilesyncUploader

    @BeforeTest
    fun setup() = runBlocking {
        tempDir = Files.createTempDirectory("filesync-upload").toString()
        source = File(tempDir, "photo.jpg").apply { writeBytes(ByteArray(5000) { it.toByte() }) }.path
        credentials.setActiveCredentials(
            ApiCredentials.create(OdinId("frodo.dotyou.cloud"), "token", SecureByteArray(ByteArray(16) { 3 }))
        )
        val fileOps = OkioFileOperationsProvider(FileSystem.SYSTEM, tempDir)
        uploader = FilesyncUploader(DriveUploadProvider(HttpClient(engine), credentials, fileOps), fileOps)
    }

    @AfterTest
    fun tearDown() {
        File(tempDir).deleteRecursively()
    }

    private val created: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond(
            OdinSystemSerializer.serialize(CreateFileResult(Uuid.random(), FilesyncProtocol.DriveAlias, newVersionTag = Uuid.random())),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private fun clientError(code: Int): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond(
            """{"title":"rejected","status":400,"errorCode":$code}""",
            HttpStatusCode.BadRequest,
            headersOf(HttpHeaders.ContentType, "application/problem+json"),
        )
    }

    private val dropped: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { throw IOException("connection reset") }

    @Test
    fun aFileIsSentWithBlake3HashesOnItsSyncDataPayload() = runTest {
        responses = mutableListOf(created)

        uploader.uploadFile(Uuid.random(), FilesyncProtocol.RootNodeId, source, "photo.jpg", null) {}

        val body = bodies.single()
        assertTrue("\"payloadKey\":\"sync_data\"" in body, body.take(600))
        assertTrue("\"algorithm\":\"blake3\"" in body, body.take(600))
        assertTrue("\"storedHash\"" in body && "\"contentHash\"" in body)
    }

    private fun realImage(name: String, format: String): String {
        val image = java.awt.image.BufferedImage(400, 300, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color.RED, 400f, 300f, java.awt.Color.BLUE)
        g.fillRect(0, 0, 400, 300)
        g.dispose()
        return File(tempDir, name).also { javax.imageio.ImageIO.write(image, format, it) }.path
    }

    private val fileOps get() = OkioFileOperationsProvider(FileSystem.SYSTEM, tempDir)

    @Test
    fun anImageGetsATinyEmbeddedPreview() = runTest {
        val preview = filesyncEmbeddedPreview(realImage("real.png", "png"), "real.png", fileOps)

        assertNotNull(preview?.content)
        assertTrue(preview!!.content!!.bytes.size <= 1024)
    }

    @Test
    fun aFileThatIsNotAnImageOrPdfGetsNoPreview() = runTest {
        val text = File(tempDir, "notes.txt").apply { writeText("hello") }.path

        assertNull(filesyncEmbeddedPreview(text, "notes.txt", fileOps))
    }

    @Test
    fun anUndecodableImageGetsNoPreviewAndTheUploadStillLands() = runTest {
        assertNull(filesyncEmbeddedPreview(source, "photo.jpg", fileOps))
        responses = mutableListOf(created)

        uploader.uploadFile(Uuid.random(), FilesyncProtocol.RootNodeId, source, "photo.jpg", null) {}

        assertTrue("\"payloadKey\":\"sync_data\"" in bodies.single())
    }

    @Test
    fun theNodeHeaderCarriesThePreview() {
        val thumb = EmbeddedThumb(8, 6, "image/webp", Base64Bytes(byteArrayOf(1, 2)))

        val metadata = filesyncMetadata(Uuid.random(), FilesyncProtocol.RootNodeId, FilesyncProtocol.FileFileType, "{}", ArchivalStatus.None, previewThumbnail = thumb)

        assertEquals(thumb, metadata.appData.previewThumbnail)
    }

    private fun hugePng(name: String, side: Int): String {
        val file = File(tempDir, name)
        fun chunk(out: java.io.DataOutputStream, type: String, data: ByteArray) {
            out.writeInt(data.size)
            val body = type.toByteArray() + data
            out.write(body)
            out.writeInt(java.util.zip.CRC32().also { it.update(body) }.value.toInt())
        }
        java.io.DataOutputStream(file.outputStream().buffered()).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            val ihdr = java.nio.ByteBuffer.allocate(13).putInt(side).putInt(side).put(1).put(0).put(0).put(0).put(0).array()
            chunk(out, "IHDR", ihdr)
            val deflater = java.util.zip.Deflater(9)
            val compressed = java.io.ByteArrayOutputStream()
            java.util.zip.DeflaterOutputStream(compressed, deflater).use { z ->
                val row = ByteArray(1 + (side + 7) / 8)
                repeat(side) { z.write(row) }
            }
            chunk(out, "IDAT", compressed.toByteArray())
            chunk(out, "IEND", ByteArray(0))
        }
        return file.path
    }

    @Test
    fun aSmallFileWithHugeDimensionsGetsNoPreviewInsteadOfADecode() = runTest {
        val path = hugePng("huge.png", 30_000)
        assertTrue(File(path).length() < 1_000_000)

        assertNull(filesyncEmbeddedPreview(path, "huge.png", fileOps))
    }

    @Test
    fun aPdfGetsATinyEmbeddedPreview() = runTest {
        val pdf = File(tempDir, "doc.pdf")
        pdf.writeText(
            "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
                "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\ntrailer<</Root 1 0 R/Size 4>>\n%%EOF\n",
        )

        val preview = filesyncEmbeddedPreview(pdf.path, "doc.pdf", fileOps)

        assertNotNull(preview?.content)
        assertTrue(preview!!.content!!.bytes.size <= 1024)
    }

    @Test
    fun uploadingAnImageSendsItsPreviewOnTheNodeHeader() = runTest {
        val sent = mutableListOf<id.homebase.api.client.drives.upload.UploadFileRequest>()
        val spy = FilesyncUploader({ request, _ -> sent += request }, fileOps, kotlin.time.Clock.System)

        spy.uploadFile(Uuid.random(), FilesyncProtocol.RootNodeId, realImage("pic.png", "png"), "pic.png", null) {}

        val preview = sent.single().metadata.appData.previewThumbnail
        assertNotNull(preview?.content)
        assertTrue(preview!!.content!!.bytes.size <= 1024)
    }

    @Test
    fun aRetryAnsweredWith4105MeansTheFirstAttemptLanded() = runTest {
        responses = mutableListOf(dropped, clientError(4105))

        uploader.uploadFile(Uuid.random(), FilesyncProtocol.RootNodeId, source, "photo.jpg", null) {}

        assertEquals(2, bodies.size)
    }

    @Test
    fun a4105OnTheFirstAttemptMeansARetryTheUserAskedForFoundItLanded() = runTest {
        responses = mutableListOf(clientError(4105))

        uploader.uploadFile(Uuid.random(), FilesyncProtocol.RootNodeId, source, "photo.jpg", null) {}

        assertEquals(1, bodies.size)
    }

    @Test
    fun aHashRejectionEncryptsAgainOnceThenGivesUp() = runTest {
        responses = mutableListOf(clientError(4175), clientError(4175))

        assertFailsWith<ClientException> { uploader.uploadFile(Uuid.random(), FilesyncProtocol.RootNodeId, source, "photo.jpg", null) {} }
        assertEquals(2, bodies.size)
    }

    @Test
    fun noEncryptedCopyIsLeftBehind() = runTest {
        responses = mutableListOf(clientError(4001))

        runCatching { uploader.uploadFile(Uuid.random(), FilesyncProtocol.RootNodeId, source, "photo.jpg", null) {} }

        assertTrue(File(tempDir).walk().none { it.name.startsWith("fs_up_") })
    }

    @Test
    fun aFolderHasNoPayload() = runTest {
        responses = mutableListOf(created)

        uploader.createFolder(FilesyncProtocol.RootNodeId, "Photos")

        assertTrue("sync_data" !in bodies.single())
    }
}
