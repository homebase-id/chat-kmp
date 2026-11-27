package id.homebase.api.client.payload

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
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Each read goes to our own drive by file id, or to the author's drive by global transit id, never the other. */
class PayloadReaderTest {

    private val driveId = Uuid.random()
    private val fileId = Uuid.random()
    private val globalTransitId = Uuid.random()
    private val peer = OdinId("sam.dotyou.cloud")
    private val key = "vid_payload"
    private val body = ByteArray(64) { it.toByte() }

    private val own = PayloadLocation.Own(driveId, fileId)
    private val peers = PayloadLocation.Peer(peer, driveId, globalTransitId)

    private val requests = mutableListOf<String>()
    private val written = mutableMapOf<String, ByteArray>()

    private val fileOps = object : FileOperationsProvider {
        private val cacheDir = "/tmp/payload-reader-test-${Uuid.random()}"
        override fun getCacheDirectory() = cacheDir
        override fun openFileInput(path: String): InputProvider = error("not used")
        override suspend fun readFileBytes(path: String): ByteArray = error("not used")
        override fun deleteTempFile(path: String) = false
        override fun getFileSize(path: String) = 0L
        override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used")
        override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used")
        override suspend fun writeStream(path: String, data: Flow<ByteArray>) {
            var all = ByteArray(0)
            data.collect { all += it }
            written[path] = all
        }
    }

    private suspend fun reader(): PayloadReader {
        val credentials = CredentialsManager()
        credentials.setActiveCredentials(ApiCredentials.create(OdinId("frodo.dotyou.cloud"), "token", SecureByteArray(ByteArray(16) { 5 })))
        val http = HttpClient(MockEngine { request ->
            requests += request.url.toString().decodeURLQueryComponent()
            respond(
                body,
                HttpStatusCode.OK,
                headersOf("payloadencrypted" to listOf("false"), HttpHeaders.ContentLength to listOf("${body.size}")),
            )
        })
        val cached = DriveFileProviderCached(http, credentials, fileOps)
        return PayloadReader(
            DriveFileProvider(http, credentials, cached),
            PeerFileByGlobalTransitProvider(http, credentials, DriveFileHttpProvider(http, credentials), cached),
            fileOps,
        )
    }

    private suspend fun PayloadReader.readEverything(at: PayloadLocation) {
        assertContentEquals(body, bytes(at, key, KeyHeader.empty())?.bytes)
        thumb(at, key, KeyHeader.empty(), width = 320, height = 320)
        encryptedChunk(at, key, chunkStart = 0, chunkLength = 32)
        assertTrue(playToPath(at, key, KeyHeader.empty(), "/out-${at::class.simpleName}.mp4"))
        assertContentEquals(body, written["/out-${at::class.simpleName}.mp4"])
    }

    @Test
    fun onlyAPostFromSomeoneWeFollowIsReadFromTheirDrive() {
        assertEquals(own, PayloadLocation.of(driveId, fileId, remoteOdinId = null, globalTransitId = null))
        assertEquals(own, PayloadLocation.of(driveId, fileId, remoteOdinId = peer, globalTransitId = null))
        assertEquals(own, PayloadLocation.of(driveId, fileId, remoteOdinId = null, globalTransitId = globalTransitId))
        assertEquals(peers, PayloadLocation.of(driveId, fileId, remoteOdinId = peer, globalTransitId = globalTransitId))
    }

    @Test
    fun ourOwnPayloadsAreReadFromOurDriveByFileId() = runTest {
        reader().readEverything(own)

        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { "/drives/$driveId/files/$fileId/payload/$key" in it }, "$requests")
        assertTrue(requests.none { "by-gtid" in it || "/peer/" in it }, "$requests")
    }

    @Test
    fun aFollowedPostsPayloadsAreReadFromTheAuthorsDriveByGlobalTransitId() = runTest {
        reader().readEverything(peers)

        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { "$peer" in it && "/drives/$driveId/files/by-gtid/$globalTransitId/payload/$key" in it }, "$requests")
        assertTrue(requests.none { "$fileId" in it }, "$requests")
    }

    @Test
    fun thePreloaderLeavesAFollowedPostsPayloadsToThePlayer() = runTest {
        val reader = reader()

        reader.prefetch(peers, key)
        reader.prefetchChunk(peers, key, chunkStart = 0, chunkLength = 32)

        assertTrue(requests.isEmpty(), "$requests")
    }

    @Test
    fun aVideoAccessReadsTheLocationItWasMadeFor() = runTest {
        reader().videoAccess(peers).getPayloadBytesDecrypted(Uuid.random(), Uuid.random(), key, KeyHeader.empty())

        assertTrue(requests.single().contains("by-gtid/$globalTransitId"), "$requests")
    }
}
