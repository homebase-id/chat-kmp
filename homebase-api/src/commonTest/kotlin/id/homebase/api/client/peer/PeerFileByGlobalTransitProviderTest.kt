@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.api.client.peer

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
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Covers the by-globalTransitId over-peer payload/thumb fetch: route shape, the plaintext
 * passthrough (public feed posts come back `payloadencrypted=false` and are returned untouched),
 * 404 → null, and the input guards. The encrypted-decrypt branch is [DriveFileProvider.decryptBytes]'
 * own responsibility (tested there); this provider only delegates to it.
 */
class PeerFileByGlobalTransitProviderTest {

    // 16-byte zero shared secret — same harness as FollowProviderTest.
    private val sharedSecret = ByteArray(16)
    private val peer = OdinId("frodo.baggins.demo.rocks")
    private val driveId = Uuid.parse("e8475dc4-6cb4-b665-1c2d-0dbd0f3aad5f")
    private val gtid = Uuid.parse("2c8cdd19-009d-a000-007d-eb88b8f218bd")

    private suspend fun creds(): CredentialsManager {
        val cm = CredentialsManager()
        cm.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId("test.homebase.id"),
                clientAccessToken = "fake-token",
                sharedSecret = SecureByteArray(sharedSecret.copyOf()),
            )
        )
        return cm
    }

    private fun provider(engine: MockEngine, cm: CredentialsManager): PeerFileByGlobalTransitProvider {
        val client = HttpClient(engine)
        val driveFileProvider = DriveFileProvider(
            client, cm, DriveFileProviderCached(client, cm, FakeFileOperationsProvider()),
        )
        return PeerFileByGlobalTransitProvider(client, cm, driveFileProvider)
    }

    @Test
    fun getPayload_hitsByGtidPayloadRoute_andReturnsPlaintextBytes() = runTest {
        var path: String? = null
        val payload = byteArrayOf(1, 2, 3, 4)
        val cm = creds()
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    "payloadencrypted" to listOf("false"),
                    "decryptedcontenttype" to listOf("image/webp"),
                ),
            )
        }

        val result = provider(engine, cm)
            .getPayloadOverPeerByGlobalTransitId(peer, driveId, gtid, "pst_mdi0")

        assertEquals(
            "/api/v2/peer/$peer/drives/$driveId/files/by-gtid/$gtid/payload/pst_mdi0",
            path,
        )
        assertEquals(payload.toList(), result!!.bytes.toList())
        assertEquals("image/webp", result.contentType)
    }

    @Test
    fun getThumb_hitsByGtidThumbRoute_withDimensions() = runTest {
        var path: String? = null
        val thumb = byteArrayOf(9, 8, 7)
        val cm = creds()
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(thumb, HttpStatusCode.OK, headersOf("payloadencrypted" to listOf("false")))
        }

        val result = provider(engine, cm)
            .getThumbOverPeerByGlobalTransitId(peer, driveId, gtid, "pst_mdi0", 320, 480)

        assertEquals(
            "/api/v2/peer/$peer/drives/$driveId/files/by-gtid/$gtid/payload/pst_mdi0/thumb/320/480",
            path,
        )
        assertEquals(thumb.toList(), result!!.bytes.toList())
    }

    @Test
    fun getPayload_returnsNullOn404() = runTest {
        val cm = creds()
        val engine = MockEngine { respond(ByteArray(0), HttpStatusCode.NotFound) }
        assertNull(
            provider(engine, cm).getPayloadOverPeerByGlobalTransitId(peer, driveId, gtid, "pst_mdi0"),
        )
    }

    @Test
    fun getThumb_returnsNullOn404() = runTest {
        val cm = creds()
        val engine = MockEngine { respond(ByteArray(0), HttpStatusCode.NotFound) }
        assertNull(
            provider(engine, cm)
                .getThumbOverPeerByGlobalTransitId(peer, driveId, gtid, "pst_mdi0", 320, 480),
        )
    }

    @Test
    fun getPayload_blankKey_throws() = runTest {
        val cm = creds()
        val engine = MockEngine { respond(ByteArray(0), HttpStatusCode.OK) }
        assertFailsWith<IllegalArgumentException> {
            provider(engine, cm).getPayloadOverPeerByGlobalTransitId(peer, driveId, gtid, "")
        }
    }

    @Test
    fun getThumb_nonPositiveDimensions_throws() = runTest {
        val cm = creds()
        val engine = MockEngine { respond(ByteArray(0), HttpStatusCode.OK) }
        assertFailsWith<IllegalArgumentException> {
            provider(engine, cm)
                .getThumbOverPeerByGlobalTransitId(peer, driveId, gtid, "pst_mdi0", 0, 480)
        }
    }
}
