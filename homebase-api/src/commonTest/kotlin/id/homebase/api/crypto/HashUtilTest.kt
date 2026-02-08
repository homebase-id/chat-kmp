package id.homebase.api.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer

/** Unit tests for HashUtil utility functions */
class HashUtilTest {

    // ========================================================================
    // SHA-256 Tests
    // ========================================================================

    @Test
    fun testSha256_ProducesCorrect32ByteHash() = runTest {
        val input = "Hello, World!".encodeToByteArray()

        val hash = HashUtil.sha256(input)

        assertEquals(32, hash.size) // SHA-256 always produces 32 bytes
    }

    @Test
    fun testSha256_SameInputSameHash() = runTest {
        val input = "Consistent input".encodeToByteArray()

        val hash1 = HashUtil.sha256(input)
        val hash2 = HashUtil.sha256(input)

        assertEquals(hash1.contentToString(), hash2.contentToString())
    }

    @Test
    fun testSha256_DifferentInputDifferentHash() = runTest {
        val input1 = "First input".encodeToByteArray()
        val input2 = "Second input".encodeToByteArray()

        val hash1 = HashUtil.sha256(input1)
        val hash2 = HashUtil.sha256(input2)

        assertNotEquals(hash1.contentToString(), hash2.contentToString())
    }

    @Test
    fun testSha256_EmptyInput() = runTest {
        val input = ByteArray(0)

        val hash = HashUtil.sha256(input)

        assertEquals(32, hash.size)
    }

    @Test
    fun testSha256_LargeInput() = runTest {
        val input = ByteArray(100000) { it.toByte() }

        val hash = HashUtil.sha256(input)

        assertEquals(32, hash.size)
    }

    // ========================================================================
    // streamSha256 Tests
    // ========================================================================

    @Test
    fun testStreamSha256_ReturnsHashAndLength() = runTest {
        val data = "Stream data".encodeToByteArray()
        val buffer = Buffer()
        buffer.write(data)

        val (hash, length) = HashUtil.streamSha256(buffer)

        assertEquals(32, hash.size)
        assertEquals(data.size.toLong(), length)
    }

    @Test
    fun testStreamSha256_WithNonce() = runTest {
        val data = "Stream data".encodeToByteArray()
        val nonce = ByteArrayUtil.getRndByteArray(16)
        val buffer = Buffer()
        buffer.write(data)

        val (hash, length) = HashUtil.streamSha256(buffer, nonce)

        assertEquals(32, hash.size)
        assertEquals(data.size.toLong(), length)
    }

    @Test
    fun testStreamSha256_NonceChangesHash() = runTest {
        val data = "Stream data".encodeToByteArray()
        val nonce1 = ByteArrayUtil.getRndByteArray(16)
        val nonce2 = ByteArrayUtil.getRndByteArray(16)

        val buffer1 = Buffer()
        buffer1.write(data)
        val (hash1, _) = HashUtil.streamSha256(buffer1, nonce1)

        val buffer2 = Buffer()
        buffer2.write(data)
        val (hash2, _) = HashUtil.streamSha256(buffer2, nonce2)

        assertNotEquals(hash1.contentToString(), hash2.contentToString())
    }

    // ========================================================================
    // HKDF Tests
    // ========================================================================

    @Test
    fun testHkdf_ProducesCorrectOutputSize() = runTest {
        val secret = ByteArrayUtil.getRndByteArray(32)
        val salt = ByteArrayUtil.getRndByteArray(16)
        val outputSize = 32

        val derivedKey = HashUtil.hkdf(secret, salt, outputSize)

        assertEquals(outputSize, derivedKey.size)
    }

    @Test
    fun testHkdf_DifferentSaltsProduceDifferentKeys() = runTest {
        val secret = ByteArrayUtil.getRndByteArray(32)
        val salt1 = ByteArrayUtil.getRndByteArray(16)
        val salt2 = ByteArrayUtil.getRndByteArray(16)

        val key1 = HashUtil.hkdf(secret, salt1, 32)
        val key2 = HashUtil.hkdf(secret, salt2, 32)

        assertNotEquals(key1.contentToString(), key2.contentToString())
    }

    @Test
    fun testHkdf_SameInputsSameOutput() = runTest {
        val secret = ByteArrayUtil.getRndByteArray(32)
        val salt = ByteArrayUtil.getRndByteArray(16)

        val key1 = HashUtil.hkdf(secret, salt, 32)
        val key2 = HashUtil.hkdf(secret, salt, 32)

        assertEquals(key1.contentToString(), key2.contentToString())
    }

    @Test
    fun testHkdf_TooSmallOutputSize_ThrowsException() = runTest {
        val secret = ByteArrayUtil.getRndByteArray(32)
        val salt = ByteArrayUtil.getRndByteArray(16)

        assertFailsWith<IllegalArgumentException> {
            HashUtil.hkdf(secret, salt, 8) // Less than 16
        }
    }

    @Test
    fun testHkdf_VariableOutputSizes() = runTest {
        val secret = ByteArrayUtil.getRndByteArray(32)
        val salt = ByteArrayUtil.getRndByteArray(16)

        val key16 = HashUtil.hkdf(secret, salt, 16)
        val key32 = HashUtil.hkdf(secret, salt, 32)
        val key64 = HashUtil.hkdf(secret, salt, 64)

        assertEquals(16, key16.size)
        assertEquals(32, key32.size)
        assertEquals(64, key64.size)
    }

    // ========================================================================
    // Constants Test
    // ========================================================================

    @Test
    fun testSha256Algorithm_CorrectValue() {
        assertEquals("SHA-256", HashUtil.SHA256_ALGORITHM)
    }
}
