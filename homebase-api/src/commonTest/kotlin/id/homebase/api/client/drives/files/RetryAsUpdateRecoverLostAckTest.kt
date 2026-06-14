package id.homebase.api.client.drives.files

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Reproduction + fix for the stuck image-to-Leela upload (homebase.log
 * 2026-06-13). An `UploadNewFile` carrying an encrypted image payload reached
 * the server and created the file, but the success ack was lost to a dropped
 * connection (`SocketException: Software caused connection abort`). Every retry
 * then got `400 ExistingFileWithUniqueId` → `retryAsUpdate`, which re-sent the
 * pre-encrypted payload under its original IV and was rejected forever with
 * `400 … When updating a file, you must change the Iv` — a ~48h loop.
 *
 * The file was already fully on the server, so the correct outcome is to
 * recognize our own already-landed create and mark the row Sent rather than
 * replay the unchangeable payload IV. [serverFileIsOurLandedCreate] is that
 * decision; [DriveOutboxUploader.retryAsUpdate] returns early when it holds.
 *
 * Mirrors the pure-function style of [RetryAsUpdateRekeyTest].
 */
class RetryAsUpdateRecoverLostAckTest {

    private val driveId = Uuid.random()
    private val uniqueId = Uuid.random()
    private val imagePayloadKey = "chat_web0"

    private fun clientRequest(
        clientKey: KeyHeader,
        payloadKeys: List<String> = listOf(imagePayloadKey),
    ): UploadFileRequest = UploadFileRequest(
        driveId = driveId,
        keyHeader = clientKey,
        metadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = true,
            appData = UploadAppFileMetaData(uniqueId = uniqueId, fileType = 7878, content = "{}"),
        ),
        payloads = payloadKeys.map { key ->
            PayloadFile(key = key, filePath = "/tmp/$key", contentType = "image/jpeg")
        },
    )

    private fun serverFile(
        keyHeader: KeyHeader,
        payloadKeys: List<String> = listOf(imagePayloadKey),
    ): HomebaseFile = HomebaseFile(
        fileId = Uuid.random(),
        driveId = driveId,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = keyHeader,
        fileMetadata = FileMetadata(
            isEncrypted = true,
            appData = AppFileMetaData(uniqueId = uniqueId, fileType = 7878),
            payloads = payloadKeys.map { PayloadDescriptor(key = it, contentType = "image/jpeg") },
        ),
        serverMetadata = ServerMetadata(),
    )

    /** Same AES key, fresh IV — what the owner reads back off the server header. */
    private fun sameKeyAs(clientKey: KeyHeader) = KeyHeader(
        iv = ByteArray(16) { 7 },
        aesKey = clientKey.aesKey,
    )

    @Test
    fun ourOwnLandedImageCreateIsRecoveredAsSent() {
        // The exact stuck scenario: our encrypted image already on the server
        // under our key. Must recover (skip the IV-reuse update).
        val clientKey = KeyHeader.newRandom16()
        assertTrue(
            serverFileIsOurLandedCreate(
                clientRequest(clientKey),
                serverFile(sameKeyAs(clientKey)),
            ),
            "server already holds our payload under our key — must complete as Sent",
        )
    }

    @Test
    fun foreignFileWithDifferentKeyDoesNotRecover() {
        // A genuinely stale/foreign file sharing the uniqueId carries a
        // different (random) key — never treat it as our landed create.
        val clientKey = KeyHeader.newRandom16()
        assertFalse(
            serverFileIsOurLandedCreate(
                clientRequest(clientKey),
                serverFile(KeyHeader.newRandom16()),
            ),
        )
    }

    @Test
    fun serverMissingOurPayloadDoesNotRecover() {
        // Our key, but the payload never landed (no payloads on the header) —
        // there is still real content to send, so do not short-circuit.
        val clientKey = KeyHeader.newRandom16()
        assertFalse(
            serverFileIsOurLandedCreate(
                clientRequest(clientKey),
                serverFile(sameKeyAs(clientKey), payloadKeys = emptyList()),
            ),
        )
    }

    @Test
    fun serverWithDifferentPayloadKeyDoesNotRecover() {
        val clientKey = KeyHeader.newRandom16()
        assertFalse(
            serverFileIsOurLandedCreate(
                clientRequest(clientKey),
                serverFile(sameKeyAs(clientKey), payloadKeys = listOf("some_other_payload")),
            ),
        )
    }

    @Test
    fun headerOnlyRequestDoesNotRecoverHere() {
        // No payloads → the header-only divergence path
        // (rekeyedUpdateForExistingServerFile) owns this case, not us.
        val clientKey = KeyHeader.newRandom16()
        assertFalse(
            serverFileIsOurLandedCreate(
                clientRequest(clientKey, payloadKeys = emptyList()),
                serverFile(sameKeyAs(clientKey)),
            ),
        )
    }

    @Test
    fun emptyServerKeyDoesNotRecover() {
        val clientKey = KeyHeader.newRandom16()
        assertFalse(
            serverFileIsOurLandedCreate(
                clientRequest(clientKey),
                serverFile(KeyHeader.empty()),
            ),
        )
    }

    @Test
    fun multiPayloadRecoversOnlyWhenAllPresent() {
        val clientKey = KeyHeader.newRandom16()
        val twoPayloads = listOf(imagePayloadKey, "chat_web1")
        assertTrue(
            serverFileIsOurLandedCreate(
                clientRequest(clientKey, payloadKeys = twoPayloads),
                serverFile(sameKeyAs(clientKey), payloadKeys = twoPayloads),
            ),
            "all our payloads present → recover",
        )
        assertFalse(
            serverFileIsOurLandedCreate(
                clientRequest(clientKey, payloadKeys = twoPayloads),
                serverFile(sameKeyAs(clientKey), payloadKeys = listOf(imagePayloadKey)),
            ),
            "one payload still missing → do not recover",
        )
    }
}
