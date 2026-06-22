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
import io.ktor.client.request.HttpRequestData
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
 * Covers the by-globalTransitId over-peer payload/thumb fetch: the transit/query route shape
 * + query params, the plaintext passthrough (public feed posts come back `payloadencrypted=false`
 * and are returned untouched), 404 → null, and the input guards. The encrypted-decrypt branch is
 * [DriveFileProvider.decryptBytes]' own responsibility (tested there); this provider only delegates.
 */
class PeerFileByGlobalTransitProviderTest {

    private val sharedSecret = ByteArray(16)
    private val peer = OdinId("frodo.baggins.demo.rocks")
    private val driveAlias = Uuid.parse("e8475dc4-6cb4-b665-1c2d-0dbd0f3aad5f")
    private val driveType = Uuid.parse("a3227ffb-a876-08be-eb24-fee9b70d92a6")
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

    private fun HttpRequestData.param(name: String): String? = url.parameters[name]

    @Test
    fun getPayload_hitsTransitPayloadRoute_withParams_andReturnsPlaintextBytes() = runTest {
        var request: HttpRequestData? = null
        val payload = byteArrayOf(1, 2, 3, 4)
        val cm = creds()
        val engine = MockEngine { req ->
            request = req
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
            .getPayloadOverPeerByGlobalTransitId(peer, driveAlias, driveType, gtid, "pst_mdi0")

        val req = requireNotNull(request)
        assertEquals("/api/apps/v1/transit/query/payload_byglobaltransitid", req.url.encodedPath)
        assertEquals(peer.toString(), req.param("odinId"))
        assertEquals(driveAlias.toString(), req.param("alias"))
        assertEquals(driveType.toString(), req.param("type"))
        assertEquals(gtid.toString(), req.param("globalTransitId"))
        assertEquals("pst_mdi0", req.param("key"))
        assertEquals(payload.toList(), result!!.bytes.toList())
        assertEquals("image/webp", result.contentType)
    }

    @Test
    fun getThumb_hitsTransitThumbRoute_withDimensions() = runTest {
        var request: HttpRequestData? = null
        val thumb = byteArrayOf(9, 8, 7)
        val cm = creds()
        val engine = MockEngine { req ->
            request = req
            respond(thumb, HttpStatusCode.OK, headersOf("payloadencrypted" to listOf("false")))
        }

        val result = provider(engine, cm)
            .getThumbOverPeerByGlobalTransitId(peer, driveAlias, driveType, gtid, "pst_mdi0", 320, 480)

        val req = requireNotNull(request)
        assertEquals("/api/apps/v1/transit/query/thumb_byglobaltransitid", req.url.encodedPath)
        assertEquals("pst_mdi0", req.param("payloadKey"))
        assertEquals("320", req.param("width"))
        assertEquals("480", req.param("height"))
        assertEquals("false", req.param("directMatchOnly"))
        assertEquals(thumb.toList(), result!!.bytes.toList())
    }

    @Test
    fun getPayload_returnsNullOn404() = runTest {
        val cm = creds()
        val engine = MockEngine { respond(ByteArray(0), HttpStatusCode.NotFound) }
        assertNull(
            provider(engine, cm)
                .getPayloadOverPeerByGlobalTransitId(peer, driveAlias, driveType, gtid, "pst_mdi0"),
        )
    }

    @Test
    fun getThumb_returnsNullOn404() = runTest {
        val cm = creds()
        val engine = MockEngine { respond(ByteArray(0), HttpStatusCode.NotFound) }
        assertNull(
            provider(engine, cm)
                .getThumbOverPeerByGlobalTransitId(peer, driveAlias, driveType, gtid, "pst_mdi0", 320, 480),
        )
    }

    @Test
    fun getPayload_blankKey_throws() = runTest {
        val cm = creds()
        val engine = MockEngine { respond(ByteArray(0), HttpStatusCode.OK) }
        assertFailsWith<IllegalArgumentException> {
            provider(engine, cm)
                .getPayloadOverPeerByGlobalTransitId(peer, driveAlias, driveType, gtid, "")
        }
    }

    @Test
    fun getThumb_nonPositiveDimensions_throws() = runTest {
        val cm = creds()
        val engine = MockEngine { respond(ByteArray(0), HttpStatusCode.OK) }
        assertFailsWith<IllegalArgumentException> {
            provider(engine, cm)
                .getThumbOverPeerByGlobalTransitId(peer, driveAlias, driveType, gtid, "pst_mdi0", 0, 480)
        }
    }
}
