package id.homebase.api.client

import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.ByteArrayUtil
import kotlinx.io.Buffer
import kotlinx.serialization.Serializable

/**
 * Represents a cryptographic key header containing an IV and AES key
 */
@Serializable
class KeyHeader(
    var iv: ByteArray,
    var aesKey: SecureByteArray
) {
    /**
     * Combines IV and AES key into a single byte array
     */
    fun combine(): SecureByteArray {
        return SecureByteArray(ByteArrayUtil.combine(iv, aesKey.unsafeBytes))
    }

    /**
     * Encrypts string data using AES-CBC and returns as a Buffer stream
     */
    suspend fun encryptDataAesAsStream(data: String): Buffer {
        return encryptDataAesAsStream(data.encodeToByteArray())
    }

    /**
     * Encrypts byte array data using AES-CBC and returns as a Buffer stream
     */
    suspend fun encryptDataAesAsStream(data: ByteArray): Buffer {
        val cipher = encryptDataAes(data)
        val buffer = Buffer()
        buffer.write(cipher)
        return buffer
    }

    /**
     * Encrypts data using AES-CBC
     */
    suspend fun encryptDataAes(data: ByteArray): ByteArray {
        return AesCbc.encrypt(
            data = data,
            key = aesKey,
            iv = iv
        )
    }

    suspend fun encryptDataAes(data: ByteArray,customIv: ByteArray?): ByteArray {
        return AesCbc.encrypt(
            data = data,
            key = aesKey,
            iv = customIv ?: iv
        )
    }

    /**
     * Decrypts data using AES-CBC with this KeyHeader's IV
     */
    suspend fun decrypt(encryptedData: ByteArray): ByteArray {
        return AesCbc.decrypt(
            cipherText = encryptedData,
            key = aesKey,
            iv = iv
        )
    }

    /**
     * Decrypts data using AES-CBC with this KeyHeader's IV
     */
    suspend fun decrypt(encryptedData: String): ByteArray {
        val encryptedBytesData = encryptedData.encodeToByteArray()
        return AesCbc.decrypt(
            cipherText = encryptedBytesData,
            key = aesKey,
            iv = iv
        )
    }

    /**
     * Decrypts data using AES-CBC with a custom IV
     * Used for payload decryption where each payload has its own IV
     */
    suspend fun decryptWithIv(encryptedData: ByteArray, customIv: ByteArray?): ByteArray {
        return AesCbc.decrypt(
            cipherText = encryptedData,
            key = aesKey,
            iv = customIv ?: iv
        )
    }


    companion object {
        /**
         * Creates a KeyHeader from combined bytes (IV + key)
         * @param data The combined byte array
         * @param ivLength Length of IV (default: 16)
         * @param keyLength Length of key (default: 16)
         */
        fun fromCombinedBytes(data: ByteArray, ivLength: Int = 16, keyLength: Int = 16): KeyHeader {
            val parts = ByteArrayUtil.split(data, ivLength, keyLength)
            return KeyHeader(
                iv = parts[0],
                aesKey = SecureByteArray(parts[1])
            )
        }

        /**
         * Creates a new KeyHeader with random 16-byte arrays
         */
        fun newRandom16(): KeyHeader {
            return KeyHeader(
                iv = ByteArrayUtil.getRndByteArray(16),
                aesKey = SecureByteArray(ByteArrayUtil.getRndByteArray(16))
            )
        }

        /**
         * Creates an empty KeyHeader with zero-filled arrays
         */
        fun empty(): KeyHeader {
            return KeyHeader(
                iv = ByteArray(16) { 0 },
                aesKey = SecureByteArray(ByteArray(16) { 0 })
            )
        }
    }
}