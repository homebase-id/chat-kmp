package id.homebase.api.crypto


import id.homebase.api.common.SecureByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/** Unit tests for AesCbc encryption/decryption */
class AesCbcTest {

    // ========================================================================
    // Basic Encryption/Decryption Tests
    // ========================================================================

    @Test
    fun testEncryptDecrypt_BasicRoundTrip() = runTest {
        val plaintext = "Hello, World!".encodeToByteArray()
        val key = ByteArrayUtil.getRndByteArray(16) // AES-128
        val iv = ByteArrayUtil.getRndByteArray(16) // CBC uses 16-byte IV

        val ciphertext = AesCbc.encrypt(plaintext, key, iv)
        val decrypted = AesCbc.decrypt(ciphertext, key, iv)

        assertEquals(plaintext.decodeToString(), decrypted.decodeToString())
    }

    @Test
    fun testEncryptDecrypt_WithSecureByteArray() = runTest {
        val plaintext = "Secure data".encodeToByteArray()
        val key = SecureByteArray(ByteArrayUtil.getRndByteArray(16))
        val iv = ByteArrayUtil.getRndByteArray(16)

        val ciphertext = AesCbc.encrypt(plaintext, key, iv)
        val decrypted = AesCbc.decrypt(ciphertext, key, iv)

        assertEquals(plaintext.decodeToString(), decrypted.decodeToString())
    }

    @Test
    fun testEncryptDecrypt_EmptyData_ThrowsException() = runTest {
        val plaintext = ByteArray(0)
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(16)

        assertFailsWith<IllegalArgumentException> { AesCbc.encrypt(plaintext, key, iv) }
    }

    @Test
    fun testEncryptDecrypt_LargeData() = runTest {
        val plaintext = ByteArray(10000) { it.toByte() }
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(16)

        val ciphertext = AesCbc.encrypt(plaintext, key, iv)
        val decrypted = AesCbc.decrypt(ciphertext, key, iv)

        assertEquals(plaintext.contentToString(), decrypted.contentToString())
    }

    // ========================================================================
    // Key Size Tests
    // ========================================================================

    @Test
    fun testEncryptDecrypt_AES128() = runTest {
        val plaintext = "AES-128 test".encodeToByteArray()
        val key = ByteArrayUtil.getRndByteArray(16) // 128 bits
        val iv = ByteArrayUtil.getRndByteArray(16)

        val ciphertext = AesCbc.encrypt(plaintext, key, iv)
        val decrypted = AesCbc.decrypt(ciphertext, key, iv)

        assertEquals(plaintext.decodeToString(), decrypted.decodeToString())
    }

    @Test
    fun testEncryptDecrypt_AES256() = runTest {
        val plaintext = "AES-256 test".encodeToByteArray()
        val key = ByteArrayUtil.getRndByteArray(32) // 256 bits
        val iv = ByteArrayUtil.getRndByteArray(16)

        val ciphertext = AesCbc.encrypt(plaintext, key, iv)
        val decrypted = AesCbc.decrypt(ciphertext, key, iv)

        assertEquals(plaintext.decodeToString(), decrypted.decodeToString())
    }

    // ========================================================================
    // IV Tests
    // ========================================================================

    @Test
    fun testEncrypt_DifferentIVsProduceDifferentCiphertext() = runTest {
        val plaintext = "Same plaintext".encodeToByteArray()
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv1 = ByteArrayUtil.getRndByteArray(16)
        val iv2 = ByteArrayUtil.getRndByteArray(16)

        val ciphertext1 = AesCbc.encrypt(plaintext, key, iv1)
        val ciphertext2 = AesCbc.encrypt(plaintext, key, iv2)

        assertNotEquals(ciphertext1.contentToString(), ciphertext2.contentToString())
    }

    @Test
    fun testEncrypt_WithRandomIV() = runTest {
        val plaintext = "Test with random IV".encodeToByteArray()
        val key = SecureByteArray(ByteArrayUtil.getRndByteArray(16))

        val (iv, ciphertext) = AesCbc.encrypt(plaintext, key)

        // Verify IV is 16 bytes (CBC standard)
        assertEquals(16, iv.size)

        // Verify we can decrypt with the returned IV
        val decrypted = AesCbc.decrypt(ciphertext, key, iv)
        assertEquals(plaintext.decodeToString(), decrypted.decodeToString())
    }

    @Test
    fun testEncrypt_InvalidIVSize_ThrowsException() = runTest {
        val plaintext = "Test".encodeToByteArray()
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(12) // Wrong size, should be 16

        assertFailsWith<IllegalArgumentException> { AesCbc.encrypt(plaintext, key, iv) }
    }

    // ========================================================================
    // Error Cases
    // ========================================================================

    @Test
    fun testEncrypt_EmptyKey_ThrowsException() = runTest {
        val plaintext = "Test".encodeToByteArray()
        val key = ByteArray(0)
        val iv = ByteArrayUtil.getRndByteArray(16)

        assertFailsWith<IllegalArgumentException> { AesCbc.encrypt(plaintext, key, iv) }
    }

    @Test
    fun testDecrypt_EmptyCiphertext_ThrowsException() = runTest {
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(16)

        assertFailsWith<IllegalArgumentException> { AesCbc.decrypt(ByteArray(0), key, iv) }
    }

    @Test
    fun testDecrypt_WrongKey_ThrowsOrProducesGarbage() = runTest {
        val plaintext = "Test message".encodeToByteArray()
        val correctKey = ByteArrayUtil.getRndByteArray(16)
        val wrongKey = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(16)

        val ciphertext = AesCbc.encrypt(plaintext, correctKey, iv)

        // CBC with PKCS padding typically throws on wrong key due to padding validation,
        // but there's a ~1/256 chance the garbage data has "valid" padding.
        // In that case, the decrypted result should still be garbage (not equal to original).
        try {
            val decrypted = AesCbc.decrypt(ciphertext, wrongKey, iv)
            // If we get here, decryption "succeeded" but should produce garbage
            assertNotEquals(
                plaintext.decodeToString(),
                decrypted.decodeToString(),
                "Decryption with wrong key should not produce original plaintext"
            )
        } catch (e: Exception) {
            // Expected: padding validation failed
        }
    }

    // ========================================================================
    // Real-World Scenarios
    // ========================================================================

    @Test
    fun testEncryptDecrypt_JSONPayload() = runTest {
        val jsonPayload = """{"user":"alice","token":"abc123","timestamp":1234567890}"""
        val plaintext = jsonPayload.encodeToByteArray()
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(16)

        val ciphertext = AesCbc.encrypt(plaintext, key, iv)
        val decrypted = AesCbc.decrypt(ciphertext, key, iv)
        val decryptedJson = decrypted.decodeToString()

        assertEquals(jsonPayload, decryptedJson)
    }

    // ========================================================================
    // Stream Encryption/Decryption Tests
    // ========================================================================

    @Test
    fun testStreamEncryptDecrypt_BasicRoundTrip() = runTest {
        val plaintext = "Hello, World!!!!".encodeToByteArray() // 16 bytes (one block)
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(16)

        // Encrypt using stream
        val dataStream = flowOf(plaintext)
        val encryptedChunks = mutableListOf<ByteArray>()
        AesCbc.streamEncryptWithCbc(dataStream, key, iv).toList(encryptedChunks)

        // Verify output size matches regular encryption
        val encryptedCombined = encryptedChunks.reduce { acc, bytes -> acc + bytes }
        val encryptedRegular = AesCbc.encrypt(plaintext, key, iv)
        assertEquals(encryptedRegular.size, encryptedCombined.size)
        assertEquals(encryptedRegular.contentToString(), encryptedCombined.contentToString())

        // Decrypt using stream - feed the same chunks as output by encrypt
        val decryptedChunks = mutableListOf<ByteArray>()
        AesCbc.streamDecryptWithCbc(flowOf(*encryptedChunks.toTypedArray()), key, iv)
            .toList(decryptedChunks)
        val decrypted = decryptedChunks.reduce { acc, bytes -> acc + bytes }

        assertEquals(plaintext.decodeToString(), decrypted.decodeToString())
    }

    @Test
    fun testStreamEncrypt_MatchesRegularEncryption() = runTest {
        val chunk1 = ByteArray(32) { 'A'.code.toByte() } // 32 bytes (2 blocks)
        val chunk2 = ByteArray(32) { 'B'.code.toByte() } // 32 bytes (2 blocks)
        val chunk3 = ByteArray(16) { 'C'.code.toByte() } // 16 bytes (1 block)
        val originalData = chunk1 + chunk2 + chunk3

        val key = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(16)

        // Encrypt using stream
        val dataStream = flowOf(chunk1, chunk2, chunk3)
        val encryptedChunks = mutableListOf<ByteArray>()
        AesCbc.streamEncryptWithCbc(dataStream, key, iv).toList(encryptedChunks)
        val encryptedStream = encryptedChunks.reduce { acc, bytes -> acc + bytes }

        // Verify stream encryption produces same result as regular encryption
        val encryptedRegular = AesCbc.encrypt(originalData, key, iv)
        assertEquals(encryptedRegular.contentToString(), encryptedStream.contentToString())
    }

    @Test
    fun testStreamEncryptDecrypt_SingleChunk() = runTest {
        val plaintext = ByteArray(64) { it.toByte() } // 64 bytes (4 blocks)
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(16)

        // Encrypt using stream
        val encryptedChunks = mutableListOf<ByteArray>()
        AesCbc.streamEncryptWithCbc(flowOf(plaintext), key, iv).toList(encryptedChunks)

        // Decrypt using stream - feed the same chunks as output by encrypt
        val decryptedChunks = mutableListOf<ByteArray>()
        AesCbc.streamDecryptWithCbc(flowOf(*encryptedChunks.toTypedArray()), key, iv)
            .toList(decryptedChunks)
        val decrypted = decryptedChunks.reduce { acc, bytes -> acc + bytes }

        assertEquals(plaintext.contentToString(), decrypted.contentToString())
    }

    @Test
    fun testStreamEncrypt_ProducesValidOutput() = runTest {
        val chunkSize = 1024 // 1KB chunks (multiple of 16)
        val numChunks = 5
        val chunks =
            (0 until numChunks).map { chunkIndex ->
                ByteArray(chunkSize) { ((chunkIndex * chunkSize + it) % 256).toByte() }
            }
        val originalData = chunks.reduce { acc, bytes -> acc + bytes }

        val key = ByteArrayUtil.getRndByteArray(32) // AES-256
        val iv = ByteArrayUtil.getRndByteArray(16)

        // Encrypt using stream
        val dataStream = flowOf(*chunks.toTypedArray())
        val encryptedChunks = mutableListOf<ByteArray>()
        AesCbc.streamEncryptWithCbc(dataStream, key, iv).toList(encryptedChunks)
        val encryptedStream = encryptedChunks.reduce { acc, bytes -> acc + bytes }

        // Verify output size (should be original + padding block)
        assertEquals(originalData.size + 16, encryptedStream.size)

        // Verify against regular encryption
        val encryptedRegular = AesCbc.encrypt(originalData, key, iv)
        assertEquals(encryptedRegular.contentToString(), encryptedStream.contentToString())
    }

    @Test
    fun testStreamEncryptDecrypt_WithSecureByteArrayKey() = runTest {
        val plaintext = "Secure stream test!".encodeToByteArray()
        // Pad to 32 bytes (multiple of 16)
        val paddedPlaintext = plaintext + ByteArray(32 - plaintext.size)

        val key = SecureByteArray(ByteArrayUtil.getRndByteArray(16))
        val iv = ByteArrayUtil.getRndByteArray(16)

        // Encrypt with SecureByteArray key
        val encryptedChunks = mutableListOf<ByteArray>()
        AesCbc.streamEncryptWithCbc(flowOf(paddedPlaintext), key, iv).toList(encryptedChunks)

        // Decrypt using stream - feed the same chunks as output by encrypt
        val decryptedChunks = mutableListOf<ByteArray>()
        AesCbc.streamDecryptWithCbc(flowOf(*encryptedChunks.toTypedArray()), key, iv)
            .toList(decryptedChunks)
        val decrypted = decryptedChunks.reduce { acc, bytes -> acc + bytes }

        assertEquals(paddedPlaintext.contentToString(), decrypted.contentToString())
    }

    @Test
    fun testStreamEncrypt_EmptyKey_ThrowsException() = runTest {
        val plaintext = ByteArray(16) { it.toByte() }
        val key = ByteArray(0)
        val iv = ByteArrayUtil.getRndByteArray(16)

        val dataStream = flowOf(plaintext)

        assertFailsWith<IllegalArgumentException> {
            AesCbc.streamEncryptWithCbc(dataStream, key, iv).toList()
        }
    }

    @Test
    fun testStreamDecrypt_InvalidIVSize_ThrowsException() = runTest {
        val data = ByteArray(16) { it.toByte() }
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(12) // Wrong size

        val dataStream = flowOf(data)

        assertFailsWith<IllegalArgumentException> {
            AesCbc.streamDecryptWithCbc(dataStream, key, iv).toList()
        }
    }
}
