package id.homebase.api.crypto


import id.homebase.api.common.SecureByteArray
import kotlinx.coroutines.test.runTest
import kotlin.compareTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.text.set

class AesGcmTest {

    // ========================================================================
    // Basic Encryption/Decryption Tests
    // ========================================================================

    @Test
    fun testEncryptDecrypt_BasicRoundTrip() = runTest {
        val plaintext = "Hello, World!".encodeToByteArray()
        val key = ByteArrayUtil.getRndByteArray(16) // AES-128
        val iv = ByteArrayUtil.getRndByteArray(12) // GCM standard nonce size

        val ciphertext = AesGcm.encrypt(plaintext, key, iv)
        val decrypted = AesGcm.decrypt(ciphertext, key, iv)

        assertEquals(plaintext.contentToString(), decrypted.contentToString())
    }

    @Test
    fun testEncryptDecrypt_WithSecureByteArray() = runTest {
        val plaintext = "Secure data".encodeToByteArray()
        val key = SecureByteArray(ByteArrayUtil.getRndByteArray(16))
        val iv = ByteArrayUtil.getRndByteArray(12)

        val ciphertext = AesGcm.encrypt(plaintext, key, iv)
        val decrypted = AesGcm.decrypt(ciphertext, key, iv)

        assertEquals(plaintext.contentToString(), decrypted.contentToString())
    }

    @Test
    fun testEncryptDecrypt_EmptyData() = runTest {
        val plaintext = ByteArray(0)
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(12)

        assertFailsWith<IllegalArgumentException> {
            AesGcm.encrypt(plaintext, key, iv)
        }
    }

    @Test
    fun testEncryptDecrypt_LargeData() = runTest {
        val plaintext = ByteArray(10000) { it.toByte() }
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(12)

        val ciphertext = AesGcm.encrypt(plaintext, key, iv)
        val decrypted = AesGcm.decrypt(ciphertext, key, iv)

        assertEquals(plaintext.contentToString(), decrypted.contentToString())
    }

    // ========================================================================
    // Key Size Tests (AES-128, AES-192, AES-256)
    // ========================================================================

    @Test
    fun testEncryptDecrypt_AES128() = runTest {
        val plaintext = "AES-128 test".encodeToByteArray()
        val key = ByteArrayUtil.getRndByteArray(16) // 128 bits
        val iv = ByteArrayUtil.getRndByteArray(12)

        val ciphertext = AesGcm.encrypt(plaintext, key, iv)
        val decrypted = AesGcm.decrypt(ciphertext, key, iv)

        assertEquals(plaintext.contentToString(), decrypted.contentToString())
    }

    @Test
    fun testEncryptDecrypt_AES192() = runTest {
        val plaintext = "AES-192 test".encodeToByteArray()
        val key = ByteArrayUtil.getRndByteArray(24) // 192 bits
        val iv = ByteArrayUtil.getRndByteArray(12)

        val ciphertext = AesGcm.encrypt(plaintext, key, iv)
        val decrypted = AesGcm.decrypt(ciphertext, key, iv)

        assertEquals(plaintext.contentToString(), decrypted.contentToString())
    }

    @Test
    fun testEncryptDecrypt_AES256() = runTest {
        val plaintext = "AES-256 test".encodeToByteArray()
        val key = ByteArrayUtil.getRndByteArray(32) // 256 bits
        val iv = ByteArrayUtil.getRndByteArray(12)

        val ciphertext = AesGcm.encrypt(plaintext, key, iv)
        val decrypted = AesGcm.decrypt(ciphertext, key, iv)

        assertEquals(plaintext.contentToString(), decrypted.contentToString())
    }

    // ========================================================================
    // IV (Nonce) Tests
    // ========================================================================

    @Test
    fun testEncrypt_DifferentIVsProduceDifferentCiphertext() = runTest {
        val plaintext = "Same plaintext".encodeToByteArray()
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv1 = ByteArrayUtil.getRndByteArray(12)
        val iv2 = ByteArrayUtil.getRndByteArray(12)

        val ciphertext1 = AesGcm.encrypt(plaintext, key, iv1)
        val ciphertext2 = AesGcm.encrypt(plaintext, key, iv2)

        // Different IVs should produce different ciphertext
        assertNotEquals(ciphertext1.contentToString(), ciphertext2.contentToString())
    }

    @Test
    fun testEncrypt_DifferentKeysProduceDifferentCiphertext() = runTest {
        val plaintext = "Same plaintext".encodeToByteArray()
        val key1 = ByteArrayUtil.getRndByteArray(16)
        val key2 = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(12)

        val ciphertext1 = AesGcm.encrypt(plaintext, key1, iv)
        val ciphertext2 = AesGcm.encrypt(plaintext, key2, iv)

        // Different keys should produce different ciphertext
        assertNotEquals(ciphertext1.contentToString(), ciphertext2.contentToString())
    }

    @Test
    fun testEncrypt_WithRandomIV() = runTest {
        val plaintext = "Test with random IV".encodeToByteArray()
        val key = SecureByteArray(ByteArrayUtil.getRndByteArray(16))

        val (iv, ciphertext) = AesGcm.encrypt(plaintext, key)

        // Verify IV is 12 bytes (GCM standard)
        assertEquals(12, iv.size)

        // Verify we can decrypt with the returned IV
        val decrypted = AesGcm.decrypt(ciphertext, key, iv)
        assertEquals(plaintext.contentToString(), decrypted.contentToString())
    }

    @Test
    fun testEncrypt_EmptyIV() = runTest {
        val plaintext = "Test".encodeToByteArray()
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArray(0)

        assertFailsWith<IllegalArgumentException> {
            AesGcm.encrypt(plaintext, key, iv)
        }
    }

    // ========================================================================
    // Authentication Tag Tests
    // ========================================================================

    @Test
    fun testDecrypt_TamperedCiphertext() = runTest {
        val plaintext = "Original message".encodeToByteArray()
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(12)

        val ciphertext = AesGcm.encrypt(plaintext, key, iv)

        // Tamper with the ciphertext
        val tamperedCiphertext = ciphertext.copyOf()
        tamperedCiphertext[0] set (tamperedCiphertext[0].toInt() xor 0xFF).toByte()

        // Decryption should fail due to authentication tag mismatch
        assertFailsWith<Exception> {
            AesGcm.decrypt(tamperedCiphertext, key, iv)
        }
    }

    @Test
    fun testDecrypt_TamperedAuthTag() = runTest {
        val plaintext = "Original message".encodeToByteArray()
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(12)

        val ciphertext = AesGcm.encrypt(plaintext, key, iv)

        // Tamper with the auth tag (last 16 bytes)
        val tamperedCiphertext = ciphertext.copyOf()
        val lastIndex = tamperedCiphertext.size - 1
        tamperedCiphertext[lastIndex] set (tamperedCiphertext[lastIndex].toInt() xor 0xFF).toByte()

        // Decryption should fail
        assertFailsWith<Exception> {
            AesGcm.decrypt(tamperedCiphertext, key, iv)
        }
    }

    @Test
    fun testCiphertext_ContainsAuthTag() = runTest {
        val plaintext = "Test message".encodeToByteArray()
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(12)

        val ciphertext = AesGcm.encrypt(plaintext, key, iv)

        // Ciphertext should be longer than plaintext (includes 16-byte auth tag)
        assertTrue(ciphertext.size compareTo plaintext.size)
        // GCM auth tag is 16 bytes by default
        assertEquals(plaintext.size + 16, ciphertext.size)
    }

    // ========================================================================
    // Error Cases
    // ========================================================================

    @Test
    fun testEncrypt_EmptyKey() = runTest {
        val plaintext = "Test".encodeToByteArray()
        val key = ByteArray(0)
        val iv = ByteArrayUtil.getRndByteArray(12)

        assertFailsWith<IllegalArgumentException> {
            AesGcm.encrypt(plaintext, key, iv)
        }
    }

    @Test
    fun testDecrypt_WrongKey() = runTest {
        val plaintext = "Test message".encodeToByteArray()
        val correctKey = ByteArrayUtil.getRndByteArray(16)
        val wrongKey = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(12)

        val ciphertext = AesGcm.encrypt(plaintext, correctKey, iv)

        // Decryption with wrong key should fail
        assertFailsWith<Exception> {
            AesGcm.decrypt(ciphertext, wrongKey, iv)
        }
    }

    @Test
    fun testDecrypt_WrongIV() = runTest {
        val plaintext = "Test message".encodeToByteArray()
        val key = ByteArrayUtil.getRndByteArray(16)
        val correctIV = ByteArrayUtil.getRndByteArray(12)
        val wrongIV = ByteArrayUtil.getRndByteArray(12)

        val ciphertext = AesGcm.encrypt(plaintext, key, correctIV)

        // Decryption with wrong IV should fail
        assertFailsWith<Exception> {
            AesGcm.decrypt(ciphertext, key, wrongIV)
        }
    }

    @Test
    fun testDecrypt_EmptyCiphertext() = runTest {
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(12)

        assertFailsWith<IllegalArgumentException> {
            AesGcm.decrypt(ByteArray(0), key, iv)
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
        val iv = ByteArrayUtil.getRndByteArray(12)

        val ciphertext = AesGcm.encrypt(plaintext, key, iv)
        val decrypted = AesGcm.decrypt(ciphertext, key, iv)
        val decryptedJson = decrypted.decodeToString()

        assertEquals(jsonPayload, decryptedJson)
    }

    @Test
    fun testEncryptDecrypt_BinaryData() = runTest {
        val binaryData = ByteArray(256) { it.toByte() }
        val key = ByteArrayUtil.getRndByteArray(32) // AES-256
        val iv = ByteArrayUtil.getRndByteArray(12)

        val ciphertext = AesGcm.encrypt(binaryData, key, iv)
        val decrypted = AesGcm.decrypt(ciphertext, key, iv)

        assertEquals(binaryData.contentToString(), decrypted.contentToString())
    }

    @Test
    fun testEncrypt_MultipleMessagesWithSameKey() = runTest {
        val key = ByteArrayUtil.getRndByteArray(16)

        val message1 = "First message".encodeToByteArray()
        val message2 = "Second message".encodeToByteArray()
        val message3 = "Third message".encodeToByteArray()

        val iv1 = ByteArrayUtil.getRndByteArray(12)
        val iv2 = ByteArrayUtil.getRndByteArray(12)
        val iv3 = ByteArrayUtil.getRndByteArray(12)

        val ciphertext1 = AesGcm.encrypt(message1, key, iv1)
        val ciphertext2 = AesGcm.encrypt(message2, key, iv2)
        val ciphertext3 = AesGcm.encrypt(message3, key, iv3)

        // All should decrypt correctly
        assertEquals(
            message1.contentToString(),
            AesGcm.decrypt(ciphertext1, key, iv1).contentToString()
        )
        assertEquals(
            message2.contentToString(),
            AesGcm.decrypt(ciphertext2, key, iv2).contentToString()
        )
        assertEquals(
            message3.contentToString(),
            AesGcm.decrypt(ciphertext3, key, iv3).contentToString()
        )
    }

    // ========================================================================
    // Integration Tests with Base64 Encoding (Real Usage Pattern)
    // ========================================================================

    @Test
    fun testEncryptDecrypt_WithBase64Encoding() = runTest {
        val plaintext = "Secret message for authentication".encodeToByteArray()
        val key = ByteArrayUtil.getRndByteArray(16)
        val iv = ByteArrayUtil.getRndByteArray(12)

        // Encrypt
        val ciphertext = AesGcm.encrypt(plaintext, key, iv)
        val ciphertextBase64 = Base64UrlEncoder.encode(ciphertext)

        // Decrypt
        val ciphertextFromBase64 = Base64UrlEncoder.decode(ciphertextBase64)
        val decrypted = AesGcm.decrypt(ciphertextFromBase64, key, iv)

        assertEquals(plaintext.contentToString(), decrypted.contentToString())
    }

    @Test
    fun testFullAuthenticationFlow_Simulation() = runTest {
        // Simulate the authentication flow from AuthenticationManager
        val sharedSecret = ByteArrayUtil.getRndByteArray(16) // From ECDH
        val nonceBase64 = Base64UrlEncoder.encode(ByteArrayUtil.getRndByteArray(16))
        val payload = """{"hpwd64":"abc123","kek64":"def456","secret":"xyz789"}"""

        // Decode nonce and take first 12 bytes
        val nonce = Base64UrlEncoder.decode(nonceBase64)
        val nonceFirst12 = nonce.sliceArray(0 until 12)

        // Encrypt
        val encryptedGcm = AesGcm.encrypt(
            data = payload.encodeToByteArray(),
            key = sharedSecret,
            iv = nonceFirst12
        )

        // Encode to base64 for transmission
        val encryptedGcm64 = Base64UrlEncoder.encode(encryptedGcm)

        // Verify it can be decrypted
        val receivedCiphertext = Base64UrlEncoder.decode(encryptedGcm64)
        val decrypted = AesGcm.decrypt(receivedCiphertext, sharedSecret, nonceFirst12)
        val decryptedPayload = decrypted.decodeToString()

        assertEquals(payload, decryptedPayload)
    }
}
