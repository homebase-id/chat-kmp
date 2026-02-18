package id.homebase.api.client

import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.encodeUrl
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.toBase64
import kotlin.io.encoding.Base64

/**
 * Helper utilities for Odin cryptography operations
 * Port of C# Helper class from YouAuthClientReferenceImplementation
 */
object CryptoHelper {

    /**
     * Combines multiple query strings into one
     */
    fun combineQueryStrings(vararg queryStrings: String): String {
        return queryStrings
            .filter { it.isNotEmpty() }
            .joinToString("&")
    }

    fun generateIv(): ByteArray {
        return ByteArrayUtil.getRndByteArray(16)
    }

    /**
     * Deserializes JSON string to type T
     */
    inline fun <reified T> deserialize(json: String): T {
        return OdinSystemSerializer.deserialize<T>(json)
            ?: throw Exception("Error deserializing $json")
    }

    /**
     * Serializes value to JSON string
     */
    inline fun <reified T> serialize(value: T): String {
        return OdinSystemSerializer.serialize(value)
    }

    /**
     * Builds a URI with encrypted query string
     * @param uri The full URI including path and query string
     * @param sharedSecretBase64 The shared secret in base64 format
     * @return URI with query string encrypted and replaced with ss parameter
     */
    suspend fun uriWithEncryptedQueryString(uri: String, sharedSecretBase64: String): String {
        return uriWithEncryptedQueryString(uri, Base64.Default.decode(sharedSecretBase64))
    }

    /**
     * Builds a URI with encrypted query string
     * @param uri The full URI including path and query string
     * @param sharedSecret The shared secret as byte array
     * @return URI with query string encrypted and replaced with ss parameter
     */
    suspend fun uriWithEncryptedQueryString(uri: String, sharedSecret: ByteArray): String {
        val queryIndex = uri.indexOf('?')
        if (queryIndex == -1 || queryIndex == uri.length - 1) {
            return uri
        }

        val path = uri.substring(0, queryIndex)
        val query = uri.substring(queryIndex + 1)

        // Generate random IV
        val iv = ByteArrayUtil.getRndByteArray(16)

        // Encrypt query string
        val encryptedBytes = AesCbc.encrypt(query.encodeToByteArray(), sharedSecret, iv)

        // Build payload
        val payload = SharedSecretEncryptedPayload(
            iv = iv.toBase64(),
            data = encryptedBytes.toBase64()
        )

        // Serialize and URL encode
        val serializedPayload = OdinSystemSerializer.serialize(payload)
        val encodedPayload = encodeUrl(serializedPayload)

        return "$path?ss=$encodedPayload"
    }

    suspend fun encryptData(plainText: String, sharedSecret: ByteArray): SharedSecretEncryptedPayload {
        val iv = generateIv()
        val encryptedBytes = AesCbc.encrypt(plainText.encodeToByteArray(), sharedSecret, iv)

        return SharedSecretEncryptedPayload(
            iv = Base64.encode(iv),
            data = Base64.encode(encryptedBytes)
        )
    }


    /**
     * Decrypts content and deserializes to type T
     * @param content The encrypted content as JSON string
     * @param sharedSecretBase64 The shared secret in base64 format
     * @return Decrypted and deserialized object of type T
     */
    suspend inline fun <reified T> decryptContent(content: String, sharedSecretBase64: String): T {
        return decryptContent(content, Base64.Default.decode(sharedSecretBase64))
    }

    /**
     * Decrypts content and deserializes to type T
     * @param content The encrypted content as JSON string
     * @param sharedSecret The shared secret as byte array
     * @return Decrypted and deserialized object of type T
     */
    suspend inline fun <reified T> decryptContent(content: String, sharedSecret: ByteArray): T {
        val plainText = decryptContentAsString(content, sharedSecret)
        return deserialize<T>(plainText)
    }

    /**
     * Decrypts content to plain string
     * @param content The encrypted content as JSON string
     * @param sharedSecretBase64 The shared secret in base64 format
     * @return Decrypted plain text string
     */
    suspend fun decryptContentAsString(content: String, sharedSecretBase64: String): String {
        return decryptContentAsString(content, Base64.Default.decode(sharedSecretBase64))
    }

    /**
     * Decrypts content to plain string
     * @param content The encrypted content as JSON string
     * @param sharedSecret The shared secret as byte array
     * @return Decrypted plain text string
     */
    suspend fun decryptContentAsString(content: String, sharedSecret: ByteArray): String {
        // Deserialize the encrypted payload
        val payload = OdinSystemSerializer.deserialize<SharedSecretEncryptedPayload>(content)

        // Decrypt the data
        val iv = Base64.Default.decode(payload.iv)
        val encryptedData = Base64.Default.decode(payload.data)
        val plainBytes = AesCbc.decrypt(encryptedData, sharedSecret, iv)

        // Convert to string
        return plainBytes.decodeToString()
    }
}