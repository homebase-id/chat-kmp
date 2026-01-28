package id.homebase.api.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import id.homebase.api.common.SecureByteArray

/**
 * AES-GCM encryption/decryption utilities using cryptography-kotlin
 *
 * AES-GCM (Galois/Counter Mode) provides authenticated encryption with associated data (AEAD).
 * The authentication tag is automatically appended to the ciphertext during encryption
 * and verified during decryption.
 */
object AesGcm {

    private val crypto = CryptographyProvider.Companion.Default
    private val aes = crypto.get(AES.GCM)

    /**
     * Encrypt data with AES-GCM using the provided key and IV (nonce)
     *
     * @param data The plaintext data to encrypt
     * @param key The AES encryption key (16, 24, or 32 bytes for AES-128, AES-192, or AES-256)
     * @param iv The initialization vector/nonce (typically 12 bytes for GCM)
     * @return The ciphertext with authentication tag appended
     */
    suspend fun encrypt(data: ByteArray, key: SecureByteArray, iv: ByteArray): ByteArray {
        return encrypt(data, key.unsafeBytes, iv)
    }

    /**
     * Encrypt data with AES-GCM using the provided key and IV (nonce)
     *
     * @param data The plaintext data to encrypt
     * @param key The AES encryption key (16, 24, or 32 bytes)
     * @param iv The initialization vector/nonce (typically 12 bytes for GCM)
     * @return The ciphertext with authentication tag appended
     */
    suspend fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(data.isNotEmpty()) { "Data cannot be empty" }
        require(key.isNotEmpty()) { "Key cannot be empty" }
        require(iv.isNotEmpty()) { "IV cannot be empty" }

        val aesKey = aes.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key)
        val cipher = aesKey.cipher()
        return cipher.encryptWithIv(iv, data)
    }

    /**
     * Encrypt data with AES-GCM using the provided key and a randomly generated IV (nonce)
     *
     * @param data The plaintext data to encrypt
     * @param key The AES encryption key
     * @return A pair of (IV, ciphertext with auth tag)
     */
    suspend fun encrypt(data: ByteArray, key: SecureByteArray): Pair<ByteArray, ByteArray> {
        require(data.isNotEmpty()) { "Data cannot be empty" }

        val iv = ByteArrayUtil.getRndByteArray(12) // GCM standard nonce size
        val ciphertext = encrypt(data, key.unsafeBytes, iv)

        return Pair(iv, ciphertext)
    }

    /**
     * Decrypt data with AES-GCM using the provided key and IV (nonce)
     *
     * @param cipherText The ciphertext with authentication tag
     * @param key The AES decryption key
     * @param iv The initialization vector/nonce used during encryption
     * @return The decrypted plaintext
     * @throws Exception if authentication fails
     */
    suspend fun decrypt(cipherText: ByteArray, key: SecureByteArray, iv: ByteArray): ByteArray {
        return decrypt(cipherText, key.unsafeBytes, iv)
    }

    /**
     * Decrypt data with AES-GCM using the provided key and IV (nonce)
     *
     * @param cipherText The ciphertext with authentication tag
     * @param key The AES decryption key
     * @param iv The initialization vector/nonce used during encryption
     * @return The decrypted plaintext
     * @throws Exception if authentication fails
     */
    suspend fun decrypt(cipherText: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(cipherText.isNotEmpty()) { "CipherText cannot be empty" }
        require(key.isNotEmpty()) { "Key cannot be empty" }
        require(iv.isNotEmpty()) { "IV cannot be empty" }

        val aesKey = aes.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key)
        val cipher = aesKey.cipher()
        return cipher.decryptWithIv(iv, cipherText)
    }
}