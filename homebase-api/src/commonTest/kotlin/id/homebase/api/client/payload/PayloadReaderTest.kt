package id.homebase.api.client.payload

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.systemFileSystem
import id.homebase.api.video.videoAccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import okio.Path.Companion.toPath

/** Each read goes to our own drive by file id, or to the author's drive by global transit id, never the other. */
class PayloadReaderTest {

    private val driveId = Uuid.random()
    private val fileId = Uuid.random()
    private val globalTransitId = Uuid.random()
    private val peer = OdinId("sam.dotyou.cloud")
    private val key = "vid_payload"
    private val body = ByteArray(64) { it.toByte() }
    private var served = body
    private var cdnStatus = HttpStatusCode.OK
    private val events = mutableListOf<String>()

    private val own = PayloadLocation.Own(driveId, fileId)
    private val peers = PayloadLocation.Peer(PeerFileRef(peer, globalTransitId), driveId)

    private val requests = mutableListOf<String>()
    private val written = mutableMapOf<String, ByteArray>()

    private val cacheDir = "/tmp/payload-reader-test-${Uuid.random()}"

    // The web's in-memory file system refuses to delete the cache's open journal, and needs no cleanup.
    @AfterTest
    fun removeCache() {
        runCatching { systemFileSystem.deleteRecursively(cacheDir.toPath()) }
    }

    private val fileOps = object : FileOperationsProvider {
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
            val url = request.url.toString().decodeURLQueryComponent()
            requests += url
            events += "GET $url"
            if (request.url.host.startsWith("cdn.") && cdnStatus != HttpStatusCode.OK) {
                val error = "{\"error\":\"no\"}".encodeToByteArray()
                return@MockEngine respond(error, cdnStatus, headersOf(HttpHeaders.ContentLength to listOf("${error.size}")))
            }
            respond(
                served,
                HttpStatusCode.OK,
                headersOf("payloadencrypted" to listOf("false"), HttpHeaders.ContentLength to listOf("${served.size}")),
            )
        })
        return payloadReaderOver(http, credentials, fileOps)
    }

    private suspend fun PayloadReader.readEverything(at: PayloadLocation) {
        assertContentEquals(body, bytes(at, key, KeyHeader.empty())?.bytes)
        thumb(at, key, KeyHeader.empty(), width = 320, height = 320)
        encryptedChunk(at, key, chunkStart = 0, chunkLength = 32)
        assertTrue(toFile(at, key, KeyHeader.empty(), "/out-${at::class.simpleName}.mp4", cache = true))
        assertContentEquals(body, written["/out-${at::class.simpleName}.mp4"])
        assertTrue(toFile(at, key, KeyHeader.empty(), "/preview-${at::class.simpleName}", cache = false))
        assertContentEquals(body, written["/preview-${at::class.simpleName}"])
    }

    @Test
    fun onlyAPostFromSomeoneWeFollowIsReadFromTheirDrive() {
        assertEquals(own, PayloadLocation.of(driveId, fileId, peer = null))
        assertEquals(peers, PayloadLocation.of(driveId, fileId, PeerFileRef(peer, globalTransitId)))
    }

    @Test
    fun ourOwnPayloadsAreReadFromOurDriveByFileId() = runTest {
        reader().readEverything(own)

        assertTrue(requests.all { "/drives/$driveId/files/$fileId/payload/$key" in it }, "$requests")
        assertTrue(requests.none { "by-gtid" in it || "/peer/" in it }, "$requests")
    }

    @Test
    fun aFollowedPostsPayloadsAreReadFromTheAuthorsDriveByGlobalTransitId() = runTest {
        reader().readEverything(peers)

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

    private suspend fun assertAnEditedImagesThumbIsReadAgain(at: PayloadLocation) {
        val reader = reader()
        served = byteArrayOf(1)
        reader.thumb(at, key, KeyHeader.empty(), width = 320, height = 320, lastModified = 1)
        served = byteArrayOf(2)

        val afterTheEdit = reader.thumb(at, key, KeyHeader.empty(), width = 320, height = 320, lastModified = 2)

        assertContentEquals(byteArrayOf(2), afterTheEdit?.bytes)
        // The CDN caches by URL: an edit must ask for a different one, or it serves the old thumb.
        assertTrue(requests.last().contains("lastModified=2"), "${requests.last()}")
        if (at is PayloadLocation.Peer) {
            cdnStatus = HttpStatusCode.Unauthorized
            reader.thumb(at, key, KeyHeader.empty(), width = 320, height = 320, lastModified = 3)
            assertTrue(requests.last().let { "/peer/" in it && "lastModified=3" in it }, "the author's server too: ${requests.last()}")
        }
    }

    private suspend fun assertAnEditedPayloadIsReadAgain(at: PayloadLocation) {
        val reader = reader()
        served = byteArrayOf(1)
        reader.bytes(at, key, KeyHeader.empty(), version = 1)
        served = byteArrayOf(2)

        assertContentEquals(byteArrayOf(2), reader.bytes(at, key, KeyHeader.empty(), version = 2)?.bytes)
        assertContentEquals(byteArrayOf(2), reader.bytes(at, key, KeyHeader.empty(), version = 2)?.bytes, "and keeps it")
        assertTrue(requests.last().contains("lastModified=2"), "${requests.last()}")
    }

    @Test
    fun anEditedPayloadOfOursIsReadAgain() = runTest { assertAnEditedPayloadIsReadAgain(own) }

    @Test
    fun anEditedPayloadOfAFollowedPostIsReadAgain() = runTest { assertAnEditedPayloadIsReadAgain(peers) }

    @Test
    fun anEditedImageOfOursShowsItsNewThumb() = runTest { assertAnEditedImagesThumbIsReadAgain(own) }

    @Test
    fun anEditedImageOfAFollowedPostShowsItsNewThumb() = runTest { assertAnEditedImagesThumbIsReadAgain(peers) }

    private suspend fun PayloadReader.progressReading(at: PayloadLocation, chunkStart: Long? = null): List<Float> {
        val seen = mutableListOf<Float>()
        bytes(at, key, KeyHeader.empty(), chunkStart = chunkStart, onProgress = { seen += it })
        return seen
    }

    @Test
    fun aDownloadReportsItsProgressWhereverItLives() = runTest {
        // Big enough to arrive in several reads, so progress moves before it ends.
        served = ByteArray(512 * 1024)
        // One reader: a whole payload and a range live in different caches, so each read below is a download.
        val reader = reader()
        for (at in listOf(own, peers)) {
            for (seen in listOf(reader.progressReading(at), reader.progressReading(at, chunkStart = 0))) {
                assertTrue(seen.any { it > 0f && it < 1f }, "moving, $at: $seen")
                assertEquals(1f, seen.last(), "done, $at")
            }
        }
    }

    @Test
    fun aFailedCdnReadReportsNoProgressBeforeTheAuthorsServerAnswers() = runTest {
        cdnStatus = HttpStatusCode.NotFound

        val progress = mutableListOf<Float>()
        reader().bytes(peers, key, KeyHeader.empty(), onProgress = { events += "progress"; progress += it })

        val peerRequest = events.indexOfFirst { it.startsWith("GET") && "/peer/" in it }
        assertTrue(peerRequest >= 0, "$events")
        assertTrue(events.take(peerRequest).none { it.startsWith("progress") }, "the CDN's error body isn't the download: $events")
        assertEquals(1f, progress.last())
    }

    @Test
    fun aFileReadForOnceIsNotKeptButOneForReplayIs() = runTest {
        val reader = reader()

        reader.toFile(own, key, KeyHeader.empty(), "/once", cache = false)
        reader.toFile(own, key, KeyHeader.empty(), "/again", cache = false)
        assertEquals(2, requests.size, "an uncached read downloads every time: $requests")

        reader.toFile(own, key, KeyHeader.empty(), "/play", cache = true)
        reader.toFile(own, key, KeyHeader.empty(), "/replay", cache = true)
        assertEquals(3, requests.size, "a cached read downloads once: $requests")
        assertContentEquals(body, written["/replay"])
    }

    @Test
    fun aPayloadKeyedByTheServerIsReadFromOurDrive() = runTest {
        reader().bytesWithServerKey(own, key)

        assertTrue(requests.single().contains("/drives/$driveId/files/$fileId/payload/$key"), "$requests")
    }

    @Test
    fun aResendReadsOurPayloadAndThumbAsStored() = runTest {
        val reader = reader()

        val stored = reader.resendSource()
        assertContentEquals(body, stored.storedPayloadBytes(driveId, fileId, key))
        assertContentEquals(body, stored.storedThumbBytes(driveId, fileId, key, width = 320, height = 320, lastModified = null))

        assertEquals(2, requests.size, "$requests")
        assertTrue(requests[0].endsWith("/drives/$driveId/files/$fileId/payload/$key"), requests[0])
        assertTrue("/drives/$driveId/files/$fileId/payload/$key/thumb" in requests[1] && "320" in requests[1], requests[1])
    }

    @Test
    fun aVideoAccessReadsTheLocationItWasMadeFor() = runTest {
        reader().videoAccess(peers).getPayloadBytesDecrypted(Uuid.random(), Uuid.random(), key, KeyHeader.empty())

        assertTrue(requests.single().contains("by-gtid/$globalTransitId"), "$requests")
    }
}
