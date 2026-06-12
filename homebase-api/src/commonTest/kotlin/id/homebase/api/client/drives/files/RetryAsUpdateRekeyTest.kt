package id.homebase.api.client.drives.files

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [rekeyedUpdateForExistingServerFile] — the root-cause fix for
 * the "AES key must match" drop documented in OutboxFailureClassifier.
 *
 * Scenario: an UploadNewFile hits `ExistingFileWithUniqueId` (the server
 * already has a file with this uniqueId) and `retryAsUpdate` converts it to an
 * update. The server rejects any update whose AES key differs from the
 * existing file's, so when the client's key has diverged (local DB lost the
 * key and minted a fresh one — the web-reload case; or a replaceEnqueue
 * superseded an in-flight create that carried a different key), the update
 * must be re-encrypted with the SERVER's key, which the owner can always
 * read from the fetched header (shared-secret decryption).
 *
 * Re-keying is only possible when the request is header-only: payload and
 * thumbnail bytes are pre-encrypted on disk with the client key, and
 * re-encrypting arbitrarily large media in memory is not safe. Those requests
 * return null and take the standard path (server rejects, classifier drops —
 * the owning flows self-heal: location re-flushes, chat offers Retry).
 */
@OptIn(ExperimentalEncodingApi::class)
class RetryAsUpdateRekeyTest {

    private val driveId = Uuid.random()
    private val uniqueId = Uuid.random()
    private val serverVersionTag = Uuid.random()

    private suspend fun encryptedHeaderOnlyRequest(
        clientKey: KeyHeader,
        plaintext: String? = "hello from the client",
        payloads: List<PayloadFile> = emptyList(),
        thumbnails: List<ThumbnailFile> = emptyList(),
    ): UploadFileRequest {
        val metadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = uniqueId,
                fileType = 8888,
                content = plaintext,
            ),
        )
        return UploadFileRequest(
            driveId = driveId,
            keyHeader = clientKey,
            metadata = metadata.encryptContent(clientKey),
            payloads = payloads,
            thumbnails = thumbnails,
        )
    }

    @Test
    fun mismatchedHeaderOnlyRequestIsRekeyedWithServerKey() = runTest {
        val clientKey = KeyHeader.newRandom16()
        val serverKey = KeyHeader.newRandom16()
        val original = encryptedHeaderOnlyRequest(clientKey)

        val rekeyed = rekeyedUpdateForExistingServerFile(original, serverKey, serverVersionTag)

        assertNotNull(rekeyed, "mismatched header-only request must be re-keyed")
        val newKeyHeader = assertNotNull(rekeyed.keyHeader)
        assertEquals(serverKey.aesKey, newKeyHeader.aesKey, "update must carry the SERVER's AES key")
        assertFalse(
            newKeyHeader.iv.contentEquals(serverKey.iv),
            "IV must be freshly minted, not the server header's",
        )
        assertEquals(serverVersionTag, rekeyed.metadata.versionTag, "server versionTag must be stamped")
        assertEquals(driveId, rekeyed.driveId)
        assertEquals(uniqueId, rekeyed.uniqueId)

        // The re-encrypted content must decrypt with the NEW keyHeader back to
        // the original plaintext.
        val contentB64 = assertNotNull(rekeyed.metadata.appData.content)
        val decrypted = newKeyHeader.decrypt(Base64.decode(contentB64)).decodeToString()
        assertEquals("hello from the client", decrypted)
    }

    @Test
    fun matchingKeysReturnNull_standardPathSuffices() = runTest {
        val clientKey = KeyHeader.newRandom16()
        // Same AES key, different IV — the server only compares the key.
        val serverKey = KeyHeader(
            iv = ByteArray(16) { 7 },
            aesKey = clientKey.aesKey,
        )
        val original = encryptedHeaderOnlyRequest(clientKey)

        assertNull(
            rekeyedUpdateForExistingServerFile(original, serverKey, serverVersionTag),
            "matching keys need no re-key — the standard versionTag stamp is correct",
        )
    }

    @Test
    fun payloadCarryingRequestReturnsNull() = runTest {
        val original = encryptedHeaderOnlyRequest(
            clientKey = KeyHeader.newRandom16(),
            payloads = listOf(
                PayloadFile(key = "pyld_0001", filePath = "/tmp/nope", contentType = "image/jpeg"),
            ),
        )

        assertNull(
            rekeyedUpdateForExistingServerFile(original, KeyHeader.newRandom16(), serverVersionTag),
            "payload bytes are pre-encrypted with the client key — cannot re-key header-only",
        )
    }

    @Test
    fun thumbnailCarryingRequestReturnsNull() = runTest {
        val original = encryptedHeaderOnlyRequest(
            clientKey = KeyHeader.newRandom16(),
            thumbnails = listOf(
                ThumbnailFile(
                    key = "pyld_0001",
                    thumbnailBytes = byteArrayOf(1, 2, 3),
                    contentType = "image/jpeg",
                    pixelWidth = 8,
                    pixelHeight = 8,
                ),
            ),
        )

        assertNull(
            rekeyedUpdateForExistingServerFile(original, KeyHeader.newRandom16(), serverVersionTag),
        )
    }

    @Test
    fun nullContentRequestIsRekeyedWithoutContent() = runTest {
        val serverKey = KeyHeader.newRandom16()
        val original = encryptedHeaderOnlyRequest(
            clientKey = KeyHeader.newRandom16(),
            plaintext = null,
        )

        val rekeyed = rekeyedUpdateForExistingServerFile(original, serverKey, serverVersionTag)

        assertNotNull(rekeyed, "no content to re-encrypt — re-key is just the key swap")
        assertEquals(serverKey.aesKey, assertNotNull(rekeyed.keyHeader).aesKey)
        assertNull(rekeyed.metadata.appData.content)
    }

    @Test
    fun unencryptedRequestReturnsNull() = runTest {
        val clientKey = KeyHeader.newRandom16()
        val metadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = false,
            appData = UploadAppFileMetaData(uniqueId = uniqueId, content = "plain"),
        )
        val original = UploadFileRequest(
            driveId = driveId,
            keyHeader = clientKey,
            metadata = metadata,
        )

        assertNull(
            rekeyedUpdateForExistingServerFile(original, KeyHeader.newRandom16(), serverVersionTag),
        )
    }

    @Test
    fun emptyServerKeyReturnsNull() = runTest {
        val original = encryptedHeaderOnlyRequest(clientKey = KeyHeader.newRandom16())

        assertNull(
            rekeyedUpdateForExistingServerFile(original, KeyHeader.empty(), serverVersionTag),
            "an unencrypted/empty server header cannot be used as a re-key target",
        )
    }

    @Test
    fun recipientsAreCarriedOver() = runTest {
        val serverKey = KeyHeader.newRandom16()
        val original = encryptedHeaderOnlyRequest(clientKey = KeyHeader.newRandom16())
        val rekeyed = rekeyedUpdateForExistingServerFile(original, serverKey, serverVersionTag)
        assertNotNull(rekeyed)
        assertTrue(
            rekeyed.instructions.recipients.isEmpty(),
            "no transitOptions on the original → no recipients on the update",
        )
    }
}
