package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.ClientException
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.upload.CreateFileResult
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
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The create rules from filesync's drive/mod.rs: BLAKE3 hashes, 4105 on a retry is success, hash rejections re-encrypt once. */
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

        uploader.uploadFile(FilesyncProtocol.RootNodeId, source, "photo.jpg", null) {}

        val body = bodies.single()
        assertTrue("\"payloadKey\":\"sync_data\"" in body, body.take(600))
        assertTrue("\"algorithm\":\"blake3\"" in body, body.take(600))
        assertTrue("\"storedHash\"" in body && "\"contentHash\"" in body)
    }

    @Test
    fun aRetryAnsweredWith4105MeansTheFirstAttemptLanded() = runTest {
        responses = mutableListOf(dropped, clientError(4105))

        uploader.uploadFile(FilesyncProtocol.RootNodeId, source, "photo.jpg", null) {}

        assertEquals(2, bodies.size)
    }

    @Test
    fun a4105OnTheFirstAttemptIsAnError() = runTest {
        responses = mutableListOf(clientError(4105))

        assertFailsWith<ClientException> { uploader.uploadFile(FilesyncProtocol.RootNodeId, source, "photo.jpg", null) {} }
    }

    @Test
    fun aHashRejectionEncryptsAgainOnceThenGivesUp() = runTest {
        responses = mutableListOf(clientError(4175), clientError(4175))

        assertFailsWith<ClientException> { uploader.uploadFile(FilesyncProtocol.RootNodeId, source, "photo.jpg", null) {} }
        assertEquals(2, bodies.size)
    }

    @Test
    fun noEncryptedCopyIsLeftBehind() = runTest {
        responses = mutableListOf(clientError(4001))

        runCatching { uploader.uploadFile(FilesyncProtocol.RootNodeId, source, "photo.jpg", null) {} }

        assertTrue(File(tempDir).walk().none { it.name.startsWith("fs_up_") })
    }

    @Test
    fun aFolderHasNoPayload() = runTest {
        responses = mutableListOf(created)

        uploader.createFolder(FilesyncProtocol.RootNodeId, "Photos")

        assertTrue("sync_data" !in bodies.single())
    }
}
