package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.file.JvmFileOperationsProvider
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlinx.coroutines.test.runTest

/**
 * Locks down the streaming AES path used by [VideoPayloadProcessor.encryptVideoFile].
 *
 * The risk being protected against: if the streaming encrypt produces ciphertext
 * different from the bulk [KeyHeader.encryptDataAes] path, every uploaded video would
 * be silently corrupted on the wire. Manual playback testing on a phone catches the
 * symptom but not at CI time — these tests do.
 */
class VideoPayloadProcessorStreamEncryptTest {

    @Test
    fun encryptVideoFile_matchesBulkEncrypt_atVariousSizes() = runTest {
        // Sizes deliberately span the AES block (16) and read-chunk (65536) boundaries
        // because the new readFileAsFlow + alignedForCbc logic is what could go wrong here.
        // Specifically tests sizes that produce a sub-block tail (mod 65536 ∈ [1..15]) —
        // those are the production-corrupting cases the streaming wrapper has to handle.
        val sizes = listOf(
            1,                         // below block size — bulk-encrypt fallback
            15,                        // also below block size
            16,                        // exactly one AES block
            17,
            65535,
            65536,                     // exactly one chunk
            65537,                     // chunk + 1 byte (the 1-byte tail used to crash)
            65536 + 7,                 // chunk + sub-block tail
            5 * 1024 * 1024 + 7,       // typical largeish video + sub-block tail
        )
        val provider = JvmFileOperationsProvider()
        val processor = VideoPayloadProcessor(provider)
        val key = KeyHeader.newRandom16()

        for (size in sizes) {
            val plaintext = ByteArray(size).also { Random.nextBytes(it) }
            val inputPath = provider.writeBytesToTempFile(plaintext, "stream_in_", ".bin")

            try {
                val outputPath = processor.encryptVideoFile(inputPath, key)

                val bulkCipher = key.encryptDataAes(plaintext)
                val streamCipher = File(outputPath).readBytes()

                assertContentEquals(
                    bulkCipher,
                    streamCipher,
                    "Ciphertext mismatch at size=$size — streaming path is incompatible with bulk path",
                )
                provider.deleteTempFile(outputPath)
            } finally {
                provider.deleteTempFile(inputPath)
            }
        }
    }

    @Test
    fun encryptVideoFile_roundTripsThroughDecrypt() = runTest {
        val provider = JvmFileOperationsProvider()
        val processor = VideoPayloadProcessor(provider)
        val key = KeyHeader.newRandom16()
        val plaintext = ByteArray(5 * 1024 * 1024 + 123).also { Random.nextBytes(it) }
        val inputPath = provider.writeBytesToTempFile(plaintext, "rt_in_", ".bin")

        try {
            val outputPath = processor.encryptVideoFile(inputPath, key)
            val cipherBytes = File(outputPath).readBytes()
            val decrypted = key.decrypt(cipherBytes)
            assertContentEquals(plaintext, decrypted)
            provider.deleteTempFile(outputPath)
        } finally {
            provider.deleteTempFile(inputPath)
        }
    }

}
