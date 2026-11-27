package id.homebase.core.image

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.uuid.Uuid

/** [PayloadDescriptor.version]: an overwrite can keep lastModified and change only the uid. */
class HomebaseImageFullPayloadVersionTest {

    private var served = byteArrayOf(1, 1, 1)
    private val http = HttpClient(MockEngine {
        respond(served, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("image/png"), "payloadencrypted" to listOf("false")))
    })
    private val dir = Files.createTempDirectory("hb-full-version-test").toString()
    private val fileOps = CacheOnlyFileOps { dir }

    @AfterTest
    fun tearDown() {
        http.close()
        File(dir).deleteRecursively()
    }

    private fun loader(): HomebaseImageLoader {
        val credentials = CredentialsManager()
        runBlocking { credentials.setActiveCredentials(ApiCredentials.create(OdinId("frodobaggins.me"), "t", SecureByteArray(ByteArray(32) { 1 }))) }
        return imageLoaderOver(http, credentials, DriveFileProviderCached(http, credentials, fileOps), fileOps)
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
