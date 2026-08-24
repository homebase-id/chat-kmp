package id.homebase.api.client.peer

import id.homebase.api.client.CdnAdvertisement
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.PayloadSizePolicy
import id.homebase.api.client.PayloadTooLargeException
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileHttpProvider
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

// The over-peer routes must stay the UnifiedV2 `PeerByGtid` paths — that is what odin-core's
// `V2DrivePeerQueryByGtidController` serves; the retired V1 `/transit/query/…` shape 404s.
//
// FakeFileOperationsProvider points the disk cache at a fixed /tmp path that outlives the test run,
// so every test that asserts on network behaviour mints a fresh gtid — a reused one would be served
// from a previous run's cache entry and never reach the engine.
class PeerFileByGlobalTransitProviderTest {

    private val peer = OdinId("author.example.com")
    private val driveId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val key = "pst0mdi0"

    // CdnAdvertisement is a process-wide singleton, so a test that teaches it a base would
    // otherwise route every later test's read through the CDN and break its path assertion.
    @BeforeTest
    fun clearCdnBase() = CdnAdvertisement.reset()

    private fun keyHeader() = KeyHeader(
        iv = ByteArray(16) { 3 },
        aesKey = SecureByteArray(ByteArray(16) { 7 }),
    )

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
        return PeerFileByGlobalTransitProvider(
            httpClient = http,
            credentialsManager = cm,
            driveFileHttpProvider = DriveFileHttpProvider(http, cm),
            driveCache = DriveFileProviderCached(http, cm, FakeFileOperationsProvider()),
        )
    }

    @Test
    fun payload_hitsV2ByGtidRoute_andReturnsPlaintextBytes() = runTest {
        val gtid = Uuid.random()
        var path: String? = null
        val bytes = byteArrayOf(1, 2, 3, 4)
        // payloadencrypted absent → decryptBytes returns the bytes untouched (the public-feed case).
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
        }

        val result = provider(engine)
            .getPayloadOverPeerByGlobalTransitId(peer, driveId, gtid, key, keyHeader())

        assertEquals(
            "/api/v2/peer/$peer/drives/$driveId/files/by-gtid/$gtid/payload/$key",
            path,
        )
        assertTrue(result != null && result.bytes.contentEquals(bytes), "plaintext bytes pass through")
    }

    @Test
    fun thumb_hitsV2ByGtidThumbRoute() = runTest {
        val gtid = Uuid.random()
        var path: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(byteArrayOf(9), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/webp"))
        }

        provider(engine).getThumbOverPeerByGlobalTransitId(
            peer, driveId, gtid, key, width = 320, height = 320, keyHeader = keyHeader(),
        )

        assertEquals(
            "/api/v2/peer/$peer/drives/$driveId/files/by-gtid/$gtid/payload/$key/thumb/320/320",
            path,
        )
    }

    // A CDN-served payload carries the ciphertext and `payloadencrypted`, but no
    // `sharedsecretencryptedheader64` — the origin only mints that per authenticated request. The read
    // must therefore decrypt with the KeyHeader the caller already holds from its feed-drive row.
    @Test
    fun payload_encrypted_decryptsWithCallerKeyHeader_whenResponseCarriesNoKeyHeader() = runTest {
        val gtid = Uuid.random()
        val plaintext = "followed post media".encodeToByteArray()
        val cipher = keyHeader().encryptDataAes(plaintext)
        val engine = MockEngine {
            respond(
                cipher,
                HttpStatusCode.OK,
                headersOf("payloadencrypted", listOf("true")),
            )
        }

        val result = provider(engine)
            .getPayloadOverPeerByGlobalTransitId(peer, driveId, gtid, key, keyHeader())

        assertTrue(
            result != null && result.bytes.contentEquals(plaintext),
            "caller-supplied KeyHeader decrypts a response with no key header",
        )
    }

    // The bug this fixes: every followed-post thumbnail was re-downloaded on every view because the
    // peer read bypassed the disk cache entirely.
    @Test
    fun thumb_secondRead_isServedFromCache_withoutASecondNetworkCall() = runTest {
        val gtid = Uuid.random()
        var networkCalls = 0
        val bytes = byteArrayOf(4, 5, 6)
        val engine = MockEngine {
            networkCalls++
            respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/webp"))
        }
        val provider = provider(engine)

        val first = provider.getThumbOverPeerByGlobalTransitId(
            peer, driveId, gtid, key, width = 320, height = 320, keyHeader = keyHeader(),
        )
        val second = provider.getThumbOverPeerByGlobalTransitId(
            peer, driveId, gtid, key, width = 320, height = 320, keyHeader = keyHeader(),
        )

        assertEquals(1, networkCalls, "second read served from disk cache")
        assertTrue(first != null && first.bytes.contentEquals(bytes))
        assertTrue(second != null && second.bytes.contentEquals(bytes))
    }

    @Test
    fun payload_404_returnsNull() = runTest {
        val engine = MockEngine { respond(ByteArray(0), HttpStatusCode.NotFound) }
        assertNull(
            provider(engine)
                .getPayloadOverPeerByGlobalTransitId(peer, driveId, Uuid.random(), key, keyHeader()),
        )
    }

    // The typed throw is what `HomebaseImageLoader.fetchFullPayloadUncached` catches to keep the
    // already-rendered thumbnail.
    @Test
    fun payload_overRenderLimit_throwsPayloadTooLarge() = runTest {
        val oversized = PayloadSizePolicy.RENDER_LIMIT_BYTES + 1
        // MockEngine validates Content-Length against the body, so the oversized body has to be
        // real; sizeBytes == the advertised length proves the guard tripped on the header.
        val engine = MockEngine {
            respond(
                ByteArray(oversized.toInt()),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentLength, oversized.toString()),
            )
        }

        val e = assertFailsWith<PayloadTooLargeException> {
            provider(engine)
                .getPayloadOverPeerByGlobalTransitId(peer, driveId, Uuid.random(), key, keyHeader())
        }
        assertEquals(oversized, e.sizeBytes)
        assertEquals(PayloadSizePolicy.RENDER_LIMIT_BYTES, e.limitBytes)
    }

    @Test
    fun cdnIsUsed_onceTheHostHasAdvertisedItsBase() = runTest {
        val urls = mutableListOf<String>()
        val engine = MockEngine { request ->
            urls += request.url.toString()
            respond(
                byteArrayOf(9),
                HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf("image/jpeg"),
                    "x-odin-cdn-payload" to listOf("https://cdn.test"),
                ),
            )
        }
        val p = provider(engine)

        // First read has no base yet, so it goes over peer — and learns the base from the response.
        p.getPayloadOverPeerByGlobalTransitId(peer, driveId, Uuid.random(), key, keyHeader())
        // Second read routes through the edge, forwarding to the AUTHOR's host, not ours.
        p.getPayloadOverPeerByGlobalTransitId(peer, driveId, Uuid.random(), key, keyHeader())

        assertEquals(2, urls.size)
        assertTrue(urls[0].startsWith("https://me.example.com/"), "first read is peer: ${urls[0]}")
        assertTrue(urls[1].startsWith("https://cdn.test/?forward="), "second read is CDN: ${urls[1]}")
        assertTrue(
            urls[1].contains("https%3A%2F%2F$peer%2Fapi%2Fv2%2Fdrives"),
            "CDN forwards to the author's host: ${urls[1]}",
        )
        assertTrue(urls[1].contains("by-gtid"), "CDN uses the by-gtid route: ${urls[1]}")
    }

    @Test
    fun cdnFailure_fallsBackToPeer_andTheHostIsNotProbedAgain() = runTest {
        val urls = mutableListOf<String>()
        val engine = MockEngine { request ->
            val url = request.url.toString()
            urls += url
            when {
                // A host that does not share the worker's CDN token hard-401s.
                url.startsWith("https://cdn.test/") ->
                    respond(ByteArray(0), HttpStatusCode.Unauthorized)
                else -> respond(
                    byteArrayOf(9),
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType to listOf("image/jpeg"),
                        "x-odin-cdn-payload" to listOf("https://cdn.test"),
                    ),
                )
            }
        }
        val p = provider(engine)

        p.getPayloadOverPeerByGlobalTransitId(peer, driveId, Uuid.random(), key, keyHeader())
        val viaFallback =
            p.getPayloadOverPeerByGlobalTransitId(peer, driveId, Uuid.random(), key, keyHeader())
        p.getPayloadOverPeerByGlobalTransitId(peer, driveId, Uuid.random(), key, keyHeader())

        assertTrue(viaFallback != null, "the peer fallback still returns the bytes")
        assertEquals(
            1,
            urls.count { it.startsWith("https://cdn.test/") },
            "the host is probed once, then stays on peer: $urls",
        )
        assertEquals(
            3,
            urls.count { it.startsWith("https://me.example.com/") },
            "every read is still served, over peer: $urls",
        )
    }
}
