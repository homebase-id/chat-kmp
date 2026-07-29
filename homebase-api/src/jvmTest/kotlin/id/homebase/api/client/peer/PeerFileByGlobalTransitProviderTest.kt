package id.homebase.api.client.peer

import id.homebase.api.client.PayloadSizePolicy
import id.homebase.api.client.PayloadTooLargeException
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.profile.FakeFileOperationsProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Locks the exact over-peer routes (the thing the earlier WIP got wrong: it hit the retired V1
 * `/transit/query/{payload,thumb}_byglobaltransitid` shape and 404'd). These must stay the UnifiedV2
 * `PeerByGtid` paths, since that's what odin-core's `V2DrivePeerQueryByGtidController` serves.
 */
class PeerFileByGlobalTransitProviderTest {

    private val peer = OdinId("author.example.com")
    private val driveId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val gtid = Uuid.parse("22222222-2222-2222-2222-222222222222")
    private val key = "pst0mdi0"

    private suspend fun provider(engine: MockEngine): PeerFileByGlobalTransitProvider {
        val cm = CredentialsManager()
        val creds = ApiCredentials.create(
            domain = OdinId("me.example.com"),
            clientAccessToken = "test-token",
            sharedSecret = SecureByteArray(ByteArray(16) { 1 }),
        )
        cm.storeCredentials(creds)
        cm.setActiveCredentials(creds)
        val http = HttpClient(engine)
        val driveFileProvider = DriveFileProvider(
            httpClient = http,
            credentialsManager = cm,
            driveCache = DriveFileProviderCached(http, cm, FakeFileOperationsProvider()),
        )
        return PeerFileByGlobalTransitProvider(http, cm, driveFileProvider)
    }

    @Test
    fun payload_hitsV2ByGtidRoute_andReturnsPlaintextBytes() = runTest {
        var path: String? = null
        val bytes = byteArrayOf(1, 2, 3, 4)
        // payloadencrypted absent → decryptBytes returns the bytes untouched (the public-feed case).
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
        }

        val result = provider(engine).getPayloadOverPeerByGlobalTransitId(peer, driveId, gtid, key)

        assertEquals(
            "/api/v2/peer/$peer/drives/$driveId/files/by-gtid/$gtid/payload/$key",
            path,
        )
        assertTrue(result != null && result.bytes.contentEquals(bytes), "plaintext bytes pass through")
    }

    @Test
    fun thumb_hitsV2ByGtidThumbRoute() = runTest {
        var path: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(byteArrayOf(9), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/webp"))
        }

        provider(engine).getThumbOverPeerByGlobalTransitId(peer, driveId, gtid, key, width = 320, height = 320)

        assertEquals(
            "/api/v2/peer/$peer/drives/$driveId/files/by-gtid/$gtid/payload/$key/thumb/320/320",
            path,
        )
    }

    @Test
    fun payload_404_returnsNull() = runTest {
        val engine = MockEngine { respond(ByteArray(0), HttpStatusCode.NotFound) }
        assertNull(provider(engine).getPayloadOverPeerByGlobalTransitId(peer, driveId, gtid, key))
    }

    /**
     * A followed identity's oversized photo must be refused at the render limit (#845) rather than
     * buffered into RAM — the typed throw is what `HomebaseImageLoader.fetchFullPayloadUncached`
     * catches to keep the already-rendered thumbnail.
     */
    @Test
    fun payload_overRenderLimit_throwsPayloadTooLarge() = runTest {
        val oversized = PayloadSizePolicy.RENDER_LIMIT_BYTES + 1
        // MockEngine validates Content-Length against the body, so the oversized body has to be
        // real; what's asserted is that the guard trips on the HEADER (sizeBytes == the advertised
        // length, not -1 from the counting fallback) — i.e. before the body is buffered.
        val engine = MockEngine {
            respond(
                ByteArray(oversized.toInt()),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentLength, oversized.toString()),
            )
        }

        val e = assertFailsWith<PayloadTooLargeException> {
            provider(engine).getPayloadOverPeerByGlobalTransitId(peer, driveId, gtid, key)
        }
        assertEquals(oversized, e.sizeBytes)
        assertEquals(PayloadSizePolicy.RENDER_LIMIT_BYTES, e.limitBytes)
    }
}
