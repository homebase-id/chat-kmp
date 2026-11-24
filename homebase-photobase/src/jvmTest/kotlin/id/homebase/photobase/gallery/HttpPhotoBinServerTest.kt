package id.homebase.photobase.gallery

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.FileVersionTag
import id.homebase.api.client.drives.files.FileVersionTagBatch
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.file.OkioFileOperationsProvider
import okio.FileSystem
import java.nio.file.Files
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** What the bin's server writes put on the wire, through the real providers. */
class HttpPhotoBinServerTest {
    private val secret = ByteArray(16) { 5 }
    private val sent = mutableListOf<Pair<String, FileVersionTagBatch>>()

    private suspend fun server(): HttpPhotoBinServer {
        val credentials = CredentialsManager()
        credentials.setActiveCredentials(ApiCredentials.create(OdinId("pippin.dotyou.cloud"), "token", SecureByteArray(secret.copyOf())))
        val http = HttpClient(MockEngine { request ->
            val body = CryptoHelper.decryptContentAsString(request.body.toByteArray().decodeToString(), secret)
            sent += request.url.encodedPath.substringAfterLast('/') to OdinSystemSerializer.deserialize<FileVersionTagBatch>(body)
            respond("""{"results":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        return HttpPhotoBinServer(DriveFileHttpProvider(http, credentials), DriveFileProvider(http, credentials, DriveFileProviderCached(http, credentials, OkioFileOperationsProvider(FileSystem.SYSTEM, Files.createTempDirectory("photo-bin").toString()))))
    }

    @Test
    fun deletingForGoodKeepsEachPhotosUniqueIdOnItsTombstone() = runTest {
        server().softDelete(listOf(FileVersionTag(Uuid.random(), Uuid.random())))

        val (endpoint, batch) = sent.single()
        assertEquals("soft-delete-batch", endpoint)
        assertEquals(true, batch.tombstone?.keepUniqueId, "else another phone backs the photo up again")
    }
}
