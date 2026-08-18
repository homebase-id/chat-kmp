package id.homebase.upload

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.video.VideoPayloadProcessor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Locks down the STREAMED encryption path in [PayloadBundleEncryptionService.encryptFile]
 * (#842, folding in former #843): `readFileAsFlow → AesCbc.streamEncryptWithCbc →
 * writeStream` replaced the whole-file-in-memory `readFileBytes → encryptDataAes` one-shot.
 *
 * The risk being protected against: if the streamed ciphertext ever differs from the bulk
 * [KeyHeader.encryptDataAes] output, every uploaded non-video payload would be silently
 * corrupted on the wire — recipients decrypt with the bulk-compatible path. Same contract
 * as VideoPayloadProcessorStreamEncryptTest pins for video.
 */
class PayloadBundleEncryptFileStreamTest {

    private fun service(fileOps: FileOperationsProvider): PayloadBundleEncryptionService =
        PayloadBundleEncryptionService(
            fileOps = fileOps,
            videoProcessor = VideoPayloadProcessor(fileOps),
            eventBus = EventBus(),
        )

    private fun bundle(vararg payloads: PayloadFile) =
        PayloadBundle(payloads = payloads.toList(), thumbnails = emptyList(), previewThumbs = emptyList())

    private fun imagePayload(key: String, path: String) =
        PayloadFile(key = key, filePath = path, contentType = "image/jpeg")

    @Test
    fun encryptBundle_streamedCiphertextMatchesBulk_atVariousSizes() = runTest {
        // Sizes span the AES block (16) and the 64 KB read-chunk boundary — the shapes
        // where a streaming wrapper can silently diverge from the bulk path.
        val sizes = listOf(
            1,
            15,
            16,
            17,
            65535,
            65536,
            65537,
            65536 + 7,
            5 * 1024 * 1024 + 7,
        )
        val provider = JvmFileOperationsProvider()
        val svc = service(provider)
        val keySource = KeyHeader.newRandom16()

        for (size in sizes) {
            val plaintext = ByteArray(size).also { Random.nextBytes(it) }
            val src = provider.writeBytesToTempFile(plaintext, "stream_src_", ".bin")
            var staged: String? = null

            try {
                val result = svc.encryptBundle(
                    Uuid.random(),
                    bundle(imagePayload("p0", src)),
                    keySource.aesKey,
                    this,
                )

                val payload = result.payloads.single()
                staged = payload.filePath
                assertTrue(
                    staged.startsWith(provider.getOutboxStagingDirectory()),
                    "encrypted payload must be staged durably: $staged",
                )

                // The service generated a fresh IV per payload and put it on the result —
                // bulk-encrypt the same plaintext under that (key, IV) for the reference.
                val iv = assertNotNull(payload.iv, "encrypted payload must carry its IV")
                val bulkCipher = KeyHeader(aesKey = keySource.aesKey, iv = iv).encryptDataAes(plaintext)

                assertContentEquals(
                    bulkCipher,
                    File(staged).readBytes(),
                    "Ciphertext mismatch at size=$size — streamed encryptFile is incompatible with bulk",
                )
            } finally {
                provider.deleteTempFile(src)
                staged?.let { provider.deleteTempFile(it) }
            }
        }
    }

    @Test
    fun midStreamFailureLeavesNoPartialStagedFile() = runTest {
        val inner = JvmFileOperationsProvider()
        val src = inner.writeBytesToTempFile(ByteArray(1024), "resolved_", ".jpg")

        // The read flow dies after the first chunk — a source swept mid-read, an I/O
        // error, a revoked grant. The staged output reserved for it must be reaped:
        // a partial ciphertext left in the durable staging dir would linger until the
        // idle reap, and worse, could ship truncated if anything retried around it.
        var reservedStagingPath: String? = null
        val dyingRead = object : FileOperationsProvider by inner {
            override suspend fun createOutboxStagingPath(prefix: String, suffix: String): String =
                inner.createOutboxStagingPath(prefix, suffix).also { reservedStagingPath = it }

            override fun readFileAsFlow(path: String, chunkSize: Int): Flow<ByteArray> = flow {
                emit(ByteArray(100))
                throw RuntimeException("stream died mid-read")
            }
        }
        val svc = service(dyingRead)

        try {
            assertFailsWith<RuntimeException> {
                svc.encryptBundle(
                    Uuid.random(),
                    bundle(imagePayload("p0", src)),
                    KeyHeader.newRandom16().aesKey,
                    this,
                )
            }

            val reserved = assertNotNull(reservedStagingPath, "a staging path must have been reserved")
            assertFalse(
                File(reserved).exists(),
                "no partial staged ciphertext may survive a mid-stream failure: $reserved",
            )
        } finally {
            inner.deleteTempFile(src)
            reservedStagingPath?.let { inner.deleteTempFile(it) }
        }
    }
}
