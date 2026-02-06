package id.homebase.api.crypto

import id.homebase.homebasekmppoc.prototype.lib.crypto.CryptoHelper
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

/** Unit tests for CryptoHelper utility functions */
class CryptoHelperTest {

    // ========================================================================
    // combineQueryStrings Tests
    // ========================================================================

    @Test
    fun testCombineQueryStrings_SingleString() {
        val result = CryptoHelper.combineQueryStrings("param1=value1")

        assertEquals("param1=value1", result)
    }

    @Test
    fun testCombineQueryStrings_MultipleStrings() {
        val result =
            CryptoHelper.combineQueryStrings("param1=value1", "param2=value2", "param3=value3")

        assertEquals("param1=value1&param2=value2&param3=value3", result)
    }

    @Test
    fun testCombineQueryStrings_WithEmptyStrings() {
        val result = CryptoHelper.combineQueryStrings("param1=value1", "", "param2=value2")

        assertEquals("param1=value1&param2=value2", result)
    }

    @Test
    fun testCombineQueryStrings_AllEmpty() {
        val result = CryptoHelper.combineQueryStrings("", "", "")

        assertEquals("", result)
    }

    // ========================================================================
    // generateIv Tests
    // ========================================================================

    @Test
    fun testGenerateIv_Correct16ByteSize() {
        val iv = CryptoHelper.generateIv()

        assertEquals(16, iv.size)
    }

    @Test
    fun testGenerateIv_RandomEachTime() {
        val iv1 = CryptoHelper.generateIv()
        val iv2 = CryptoHelper.generateIv()

        assertNotEquals(iv1.contentToString(), iv2.contentToString())
    }

    // ========================================================================
    // Serialization Tests
    // ========================================================================

    @Test
    fun testSerialize_Deserialize_RoundTrip() {
        @Serializable
        data class TestData(val name: String, val value: Int)

        val original = TestData(name = "test", value = 42)

        val json = CryptoHelper.serialize(original)
        val deserialized = CryptoHelper.deserialize<TestData>(json)

        assertEquals(original.name, deserialized.name)
        assertEquals(original.value, deserialized.value)
    }

    // ========================================================================
    // uriWithEncryptedQueryString Tests
    // ========================================================================

    @Test
    fun testUriWithEncryptedQueryString_ReplacesQueryWithSS() = runTest {
        val sharedSecret = ByteArrayUtil.getRndByteArray(32)
        val uri = "https://example.com/api/path?param1=value1&param2=value2"

        val encryptedUri = CryptoHelper.uriWithEncryptedQueryString(uri, sharedSecret)

        assertTrue(encryptedUri.startsWith("https://example.com/api/path?ss="))
        assertTrue(!encryptedUri.contains("param1="))
        assertTrue(!encryptedUri.contains("param2="))
    }

    @Test
    fun testUriWithEncryptedQueryString_WithBase64Secret() = runTest {
        val sharedSecret = Base64.encode(ByteArrayUtil.getRndByteArray(32))
        val uri = "https://example.com/api?test=value"

        val encryptedUri = CryptoHelper.uriWithEncryptedQueryString(uri, sharedSecret)

        assertTrue(encryptedUri.contains("ss="))
    }

    @Test
    fun testUriWithEncryptedQueryString_NoQueryString_ReturnsOriginal() = runTest {
        val sharedSecret = ByteArrayUtil.getRndByteArray(32)
        val uri = "https://example.com/api/path"

        val encryptedUri = CryptoHelper.uriWithEncryptedQueryString(uri, sharedSecret)

        assertEquals(uri, encryptedUri)
    }

    @Test
    fun testUriWithEncryptedQueryString_EmptyQueryString_ReturnsOriginal() = runTest {
        val sharedSecret = ByteArrayUtil.getRndByteArray(32)
        val uri = "https://example.com/api/path?"

        val encryptedUri = CryptoHelper.uriWithEncryptedQueryString(uri, sharedSecret)

        assertEquals(uri, encryptedUri)
    }

    // ========================================================================
    // encryptData Tests
    // ========================================================================

    @Test
    fun testEncryptData_ProducesValidPayload() = runTest {
        val sharedSecret = ByteArrayUtil.getRndByteArray(32)
        val plainText = "Hello, World!"

        val payload = CryptoHelper.encryptData(plainText, sharedSecret)

        assertTrue(payload.iv.isNotEmpty())
        assertTrue(payload.data.isNotEmpty())
    }

    // ========================================================================
    // decryptContent Tests
    // ========================================================================

    @Test
    fun testDecryptContentAsString_RoundTrip() = runTest {
        val sharedSecret = ByteArrayUtil.getRndByteArray(32)
        val originalText = "Secret message to encrypt"

        // Encrypt
        val payload = CryptoHelper.encryptData(originalText, sharedSecret)
        val encryptedJson =
            OdinSystemSerializer.serialize(payload)

        // Decrypt
        val decrypted = CryptoHelper.decryptContentAsString(encryptedJson, sharedSecret)

        assertEquals(originalText, decrypted)
    }

    @Test
    fun testDecryptContentAsString_WithBase64Secret() = runTest {
        val sharedSecretBytes = ByteArrayUtil.getRndByteArray(32)
        val sharedSecretBase64 = Base64.encode(sharedSecretBytes)
        val originalText = "Test message"

        // Encrypt
        val payload = CryptoHelper.encryptData(originalText, sharedSecretBytes)
        val encryptedJson =
            OdinSystemSerializer.serialize(payload)

        // Decrypt with base64 secret
        val decrypted = CryptoHelper.decryptContentAsString(encryptedJson, sharedSecretBase64)

        assertEquals(originalText, decrypted)
    }

    @Test
    fun testDecryptContent_TypedDeserialization() = runTest {
        @Serializable
        data class TestPayload(val message: String, val count: Int)

        val sharedSecret = ByteArrayUtil.getRndByteArray(32)
        val originalPayload = TestPayload(message = "hello", count = 99)
        val originalJson =
            OdinSystemSerializer.serialize(
                originalPayload
            )

        // Encrypt
        val encryptedPayload = CryptoHelper.encryptData(originalJson, sharedSecret)
        val encryptedJson =
            OdinSystemSerializer.serialize(
                encryptedPayload
            )

        // Decrypt with type
        val decrypted = CryptoHelper.decryptContent<TestPayload>(encryptedJson, sharedSecret)

        assertEquals("hello", decrypted.message)
        assertEquals(99, decrypted.count)
    }
}
