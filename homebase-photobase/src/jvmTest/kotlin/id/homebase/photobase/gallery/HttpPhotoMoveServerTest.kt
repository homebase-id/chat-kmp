package id.homebase.photobase.gallery

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.FileVersionTag
import id.homebase.api.client.drives.files.FileVersionTagBatch
import id.homebase.api.client.drives.files.LocalTagsBatch
import id.homebase.api.client.drives.files.LocalTagsFile
import id.homebase.api.client.drives.upload.DriveUploadProvider
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** What the mover's server writes put on the wire, through the real providers. */
class HttpPhotoMoveServerTest {
    private val secret = ByteArray(16) { 5 }
    private val sent = mutableListOf<Pair<String, String>>()
    private val cacheDir = Files.createTempDirectory("photo-move")

    @AfterTest
    fun removeCache() = cacheDir.toFile().deleteRecursively().let { }

    private suspend fun server(): HttpPhotoMoveServer {
        val credentials = CredentialsManager()
        credentials.setActiveCredentials(ApiCredentials.create(OdinId("pippin.dotyou.cloud"), "token", SecureByteArray(secret.copyOf())))
        val http = HttpClient(MockEngine { request ->
            val body = CryptoHelper.decryptContentAsString(request.body.toByteArray().decodeToString(), secret)
            sent += request.url.encodedPath.substringAfterLast('/') to body
            respond("""{"results":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val fileOps = OkioFileOperationsProvider(FileSystem.SYSTEM, cacheDir.toString())
        return HttpPhotoMoveServer(DriveFileHttpProvider(http, credentials), DriveFileProvider(http, credentials, DriveFileProviderCached(http, credentials, fileOps)), DriveUploadProvider(http, credentials, fileOps))
    }

    @Test
    fun deletingForGoodKeepsEachPhotosUniqueIdOnItsTombstone() = runTest {
        server().softDelete(listOf(FileVersionTag(Uuid.random(), Uuid.random())))

        val (endpoint, body) = sent.single()
        assertEquals("soft-delete-batch", endpoint)
        assertEquals(true, OdinSystemSerializer.deserialize<FileVersionTagBatch>(body).tombstone?.keepUniqueId, "else another phone backs the photo up again")
    }

    @Test
    fun aHeartAddsOrRemovesTheTagOnEachPhotoWithNoVersionToCheck() = runTest {
        val tag = Uuid.random()
        val photos = List(2) { Uuid.random() }

        server().setLocalTag(tag, on = true, photos)
        server().setLocalTag(tag, on = false, photos)

        val (added, removed) = sent.map { (endpoint, body) ->
            assertEquals("update-local-tags-batch", endpoint)
            OdinSystemSerializer.deserialize<LocalTagsBatch>(body)
        }
        assertEquals(LocalTagsBatch(addTags = listOf(tag), removeTags = emptyList(), files = photos.map { LocalTagsFile(it) }), added)
        assertEquals(LocalTagsBatch(addTags = emptyList(), removeTags = listOf(tag), files = photos.map { LocalTagsFile(it) }), removed)
    }
}
