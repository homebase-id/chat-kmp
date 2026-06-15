@file:OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)

package id.homebase.api.client.contacts

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.image.ImageTestHelper
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ContactImageTest {

    private val testDomain = OdinId("test.homebase.id")
    private val secretBytes = "0123456789abcdef".encodeToByteArray() // 16-byte AES key
    private val driveId = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val uniqueId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val tagA = Uuid.parse("22222222-2222-2222-2222-222222222222")
    private val tagB = Uuid.parse("33333333-3333-3333-3333-333333333333")
    private val fileAesKey = SecureByteArray(ByteArray(16) { 7 })

    private val jsonHeaders =
        headersOf("Content-Type" to listOf(ContentType.Application.Json.toString()))

    /** Header reader returns a contact file whose key header carries [fileAesKey]. */
    private val headerReader = ContactHeaderReader { _, _ ->
        HomebaseFile(
            fileId = uniqueId,
            driveId = driveId,
            serverFileIsEncrypted = true,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = KeyHeader(iv = ByteArray(16) { 1 }, aesKey = fileAesKey),
            fileMetadata = FileMetadata(
                isEncrypted = true,
                versionTag = tagA,
                appData = AppFileMetaData(uniqueId = uniqueId, fileType = 100),
            ),
            serverMetadata = ServerMetadata(),
        )
    }

    private suspend fun provider(
        engine: MockEngine,
        reader: ContactHeaderReader = headerReader,
    ): ContactsProvider {
        val cm = CredentialsManager()
        val creds = ApiCredentials.create(
            domain = testDomain,
            clientAccessToken = "test-token",
            sharedSecret = SecureByteArray(secretBytes),
        )
        cm.storeCredentials(creds)
        cm.setActiveCredentials(creds)
        return ContactsProvider(HttpClient(engine), cm, reader)
    }

    /** Decrypts the shared-secret transport envelope back to the plaintext image request. */
    private suspend fun decryptRequest(envelope: String): SetContactImageRequest =
        OdinSystemSerializer.deserialize(CryptoHelper.decryptContentAsString(envelope, secretBytes))

    @Test
    fun requestSerializesBase64StringsAndCamelCase() {
        val req = SetContactImageRequest(
            versionTag = tagA,
            contentType = "image/jpeg",
            iv = "AAAA",
            content = "BBBB",
            thumbnails = listOf(ContactImageThumbnail(400, 400, "image/jpeg", "CCCC")),
        )
        val json = OdinSystemSerializer.serialize(req)

        assertTrue(json.contains("\"versionTag\":\"$tagA\""), json)
        assertTrue(json.contains("\"iv\":\"AAAA\""), json)
        assertTrue(json.contains("\"pixelWidth\":400"), json)
        assertEquals(req, OdinSystemSerializer.deserialize<SetContactImageRequest>(json))
    }

    @Test
    fun setImage_encryptsImageAndThumbnailsUnderFileKey() = runTest {
        val image = ImageTestHelper.loadImage("MarsRGB_tagged.jpg")
        var envelope: String? = null
        val engine = MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("/contacts/$uniqueId/image"))
            envelope = (request.body as TextContent).text
            respond(ContactFixtures.okBody("$uniqueId", "$tagB"), HttpStatusCode.OK, jsonHeaders)
        }

        val result = provider(engine)
            .setContactImage(uniqueId, driveId, image, "image/jpeg", tagA)

        assertEquals(tagB, assertIs<ContactWriteResult.Ok>(result).body.versionTag)

        // The bytes the server would store, decrypted with the file key + the sent IV, are the
        // original image — and thumbnails were generated and encrypted under the same IV.
        val sent = decryptRequest(envelope!!)
        assertTrue(sent.thumbnails.isNotEmpty())
        val kh = KeyHeader(iv = Base64.decode(sent.iv), aesKey = fileAesKey)
        assertContentEquals(image, kh.decrypt(Base64.decode(sent.content)))
        sent.thumbnails.forEach { thumb ->
            // Each thumbnail decrypts cleanly with the same key/IV (non-empty plaintext).
            assertTrue(kh.decrypt(Base64.decode(thumb.content)).isNotEmpty())
        }
    }

    @Test
    fun setImage_encryptsOnce_thenRetriesWithFreshTag() = runTest {
        val image = ImageTestHelper.loadImage("MarsRGB_tagged.jpg")
        val envelopes = mutableListOf<String>()
        val responses = listOf(
            HttpStatusCode.Conflict to ContactFixtures.conflictBody("$uniqueId", "$tagB"),
            HttpStatusCode.OK to ContactFixtures.okBody("$uniqueId", "$tagB"),
        )
        var i = 0
        val engine = MockEngine { request ->
            envelopes += (request.body as TextContent).text
            val (status, body) = responses[i++]
            respond(body, status, jsonHeaders)
        }

        val result = provider(engine)
            .setContactImage(uniqueId, driveId, image, "image/jpeg", tagA)

        assertIs<ContactWriteResult.Ok>(result)
        assertEquals(2, envelopes.size)

        // Same ciphertext on both attempts (encrypted once); only the version tag advances.
        val first = decryptRequest(envelopes[0])
        val second = decryptRequest(envelopes[1])
        assertEquals(first.content, second.content)
        assertEquals(first.iv, second.iv)
        assertEquals(tagA, first.versionTag)
        assertEquals(tagB, second.versionTag)
    }

    @Test
    fun setImage_returnsNotFound_whenHeaderMissing() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }
        val result = provider(engine, reader = ContactHeaderReader { _, _ -> null })
            .setContactImage(uniqueId, driveId, ByteArray(16), "image/jpeg", tagA)

        assertEquals(ContactWriteResult.NotFound, result)
        assertEquals(0, engine.requestHistory.size) // never reached the PUT
    }

    @Test
    fun deleteImage_putsVersionTagQuery_andReturnsNewTag() = runTest {
        val engine = MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("/contacts/$uniqueId/image"))
            assertTrue(request.url.encodedQuery.contains("versionTag=$tagA"), request.url.toString())
            respond(ContactFixtures.okBody("$uniqueId", "$tagB"), HttpStatusCode.OK, jsonHeaders)
        }
        val result = provider(engine).deleteContactImage(uniqueId, tagA)
        assertEquals(tagB, assertIs<ContactWriteResult.Ok>(result).body.versionTag)
    }

    @Test
    fun deleteImage_404_returnsNotFound() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.NotFound, jsonHeaders) }
        assertEquals(ContactWriteResult.NotFound, provider(engine).deleteContactImage(uniqueId, tagA))
    }

    @Test
    fun deleteImage_throwsWhenContentionExceedsMaxAttempts() = runTest {
        val engine = MockEngine {
            respond(ContactFixtures.conflictBody("$uniqueId", "$tagB"), HttpStatusCode.Conflict, jsonHeaders)
        }
        assertFailsWith<IllegalStateException> {
            provider(engine).deleteContactImage(uniqueId, tagA, maxAttempts = 2)
        }
    }
}
