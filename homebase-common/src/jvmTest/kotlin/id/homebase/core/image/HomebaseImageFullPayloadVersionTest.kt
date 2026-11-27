package id.homebase.core.image

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.payload.PayloadReader
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.uuid.Uuid

/** odin-core can overwrite a payload under its key (AppendOrOverwrite): a new upload has a new uid, maybe the same lastModified. */
class HomebaseImageFullPayloadVersionTest {

    private var served = byteArrayOf(1, 1, 1)
    private val http = HttpClient(MockEngine {
        respond(served, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("image/png"), "payloadencrypted" to listOf("false")))
    })
    private val dir = Files.createTempDirectory("hb-full-version-test").toString()
    private val fileOps = object : FileOperationsProvider {
        override fun getCacheDirectory() = dir
        override suspend fun readFileBytes(path: String): ByteArray = error("not used")
        override fun openFileInput(path: String): InputProvider = error("not used")
        override fun deleteTempFile(path: String) = false
        override fun getFileSize(path: String) = 0L
        override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used")
        override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used")
        override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used")
    }

    @AfterTest
    fun tearDown() {
        http.close()
        File(dir).deleteRecursively()
    }

    private fun loader(): HomebaseImageLoader {
        val credentials = CredentialsManager()
        runBlocking { credentials.setActiveCredentials(ApiCredentials.create(OdinId("frodobaggins.me"), "t", SecureByteArray(ByteArray(32) { 1 }))) }
        val cache = DriveFileProviderCached(http, credentials, fileOps)
        val reader = PayloadReader(
            DriveFileProvider(http, credentials, cache),
            PeerFileByGlobalTransitProvider(http, credentials, DriveFileHttpProvider(http, credentials), cache),
            fileOps,
        )
        return HomebaseImageLoader(reader, fileOps)
    }

    private fun image(uid: Long) = HomebaseImageData.from(
        driveId = Uuid.parse("2612429d-0000-0000-0000-000000000001"),
        fileId = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
        descriptor = PayloadDescriptor(key = "vlt_pg_02", contentType = "image/png", lastModified = 5, uid = uid),
        keyHeader = KeyHeader.empty(),
        loadFullPayload = true,
        isEncrypted = false,
    )

    @Test
    fun aReplacedImageOpensFullSizeAsTheNewOne() = runBlocking {
        val loader = loader()
        assertContentEquals(byteArrayOf(1, 1, 1), loader.loadFullPayload(image(uid = 100))?.bytes)

        served = byteArrayOf(2, 2, 2)

        assertContentEquals(byteArrayOf(2, 2, 2), loader.loadFullPayload(image(uid = 200))?.bytes)
    }
}
