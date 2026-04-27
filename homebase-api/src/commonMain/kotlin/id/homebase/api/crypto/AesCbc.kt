package id.homebase.api.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import id.homebase.api.common.SecureByteArray
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/** AES-CBC encryption/decryption utilities using cryptography-kotlin */
object AesCbc {

    private val crypto = CryptographyProvider.Companion.Default
    private val aes = crypto.get(AES.CBC)

    /**
     * Encrypt data with AES-CBC using the provided key and IV Use this when you need to reencrypt
     * with the same IV (e.g., transforming headers)
     */
    suspend fun encrypt(data: ByteArray, key: SecureByteArray, iv: ByteArray): ByteArray {
        return encrypt(data, key.unsafeBytes, iv)
    }

    /** Encrypt data with AES-CBC using the provided key and IV */
    suspend fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(data.isNotEmpty()) { "Data cannot be empty" }
        require(key.isNotEmpty()) { "Key cannot be empty" }
        require(iv.size == 16) { "IV must be 16 bytes" }

        val aesKey = aes.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key)
        val cipher = aesKey.cipher()
        return cipher.encryptWithIv(iv, data)
    }

    /**
     * Encrypt data with AES-CBC using the provided key and a randomly generated IV Returns a pair
     * of (IV, ciphertext)
     */
    suspend fun encrypt(data: ByteArray, key: SecureByteArray): Pair<ByteArray, ByteArray> {
        require(data.isNotEmpty()) { "Data cannot be empty" }

        val iv = ByteArrayUtil.getRndByteArray(16)
        val ciphertext = encrypt(data, key.unsafeBytes, iv)

        return Pair(iv, ciphertext)
    }

    /** Decrypt data with AES-CBC using the provided key and IV */
    suspend fun decrypt(cipherText: ByteArray, key: SecureByteArray, iv: ByteArray): ByteArray {
        return decrypt(cipherText, key.unsafeBytes, iv)
    }

    /** Decrypt data with AES-CBC using the provided key and IV */
    suspend fun decrypt(cipherText: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(cipherText.isNotEmpty()) { "CipherText cannot be empty" }
        require(key.isNotEmpty()) { "Key cannot be empty" }
        require(iv.size == 16) { "IV must be 16 bytes" }

        val aesKey = aes.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key)
        val cipher = aesKey.cipher()
        return cipher.decryptWithIv(iv, cipherText)
    }

    // ========================================================================
    // Stream Encryption/Decryption Functions
    // ========================================================================

    private const val BLOCK_SIZE = 16

    /**
     * Stream encrypt data with AES-CBC + PKCS7 padding. Accepts a [dataStream] of any chunk
     * shape — the function buffers internally so callers don't need to align their producer
     * to the AES block size.
     *
     * The output ciphertext is byte-identical to [encrypt] applied to the concatenation of
     * all incoming chunks: same CBC chaining, same single PKCS7 pad block at the end.
     *
     * Algorithm: keep a [carry] buffer of unprocessed bytes (always < 2 * BLOCK_SIZE). On
     * each incoming chunk, peel off the largest block-aligned prefix that still leaves at
     * least one block in carry, encrypt + emit that prefix (stripping the cipher's PKCS7
     * pad block since the input is aligned), and roll the chain IV. On flow end, encrypt
     * whatever's left in carry — the cipher applies the final PKCS7 padding for us.
     *
     * @param dataStream Flow of byte arrays representing the data stream
     * @param key Encryption key
     * @param iv Initialization vector (16 bytes)
     * @return Flow of encrypted byte arrays
     */
    fun streamEncryptWithCbc(
            dataStream: Flow<ByteArray>,
            key: ByteArray,
            iv: ByteArray
    ): Flow<ByteArray> = channelFlow {
        require(key.isNotEmpty()) { "Key cannot be empty" }
        require(iv.size == BLOCK_SIZE) { "IV must be $BLOCK_SIZE bytes" }

        val aesKey = aes.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key)
        val cipher = aesKey.cipher()

        var carry = ByteArray(0)
        var chainIv = iv

        dataStream.collect { incoming ->
            if (incoming.isEmpty()) return@collect
            val combined = if (carry.isEmpty()) incoming else carry + incoming
            // Hold back at least BLOCK_SIZE bytes so the eventual final emit is always
            // ≥ BLOCK_SIZE — required because we strip a PKCS7 pad block from each
            // mid-stream encryption, which would underflow if the chunk were too small.
            val processSize = ((combined.size - BLOCK_SIZE) / BLOCK_SIZE) * BLOCK_SIZE
            if (processSize > 0) {
                val toEncrypt = combined.copyOfRange(0, processSize)
                val encrypted = cipher.encryptWithIv(chainIv, toEncrypt)
                // Strip the PKCS7 pad block (always exactly BLOCK_SIZE since input is aligned).
                val streamed = encrypted.copyOfRange(0, encrypted.size - BLOCK_SIZE)
                chainIv = streamed.copyOfRange(streamed.size - BLOCK_SIZE, streamed.size)
                carry = combined.copyOfRange(processSize, combined.size)
                send(streamed)
            } else {
                carry = combined
            }
        }

        // Final emit: encrypt whatever's left in carry (0..N bytes) — the cipher's own
        // PKCS7 padding produces the trailing block(s).
        send(cipher.encryptWithIv(chainIv, carry))
    }

    /** Stream encrypt data with AES-CBC using SecureByteArray key. */
    fun streamEncryptWithCbc(
            dataStream: Flow<ByteArray>,
            key: SecureByteArray,
            iv: ByteArray
    ): Flow<ByteArray> = streamEncryptWithCbc(dataStream, key.toByteArray(), iv)

    /**
     * Stream decrypt data with AES-CBC. Assumes each chunk (apart from last one) is a multiple of
     * 16 bytes.
     *
     * The algorithm:
     * 1. For each chunk, add artificial padding to enable decryption
     * 2. Decrypt using the previous block as IV (or initial IV for first chunk)
     * 3. If decryption fails (last block with real padding), retry without artificial padding
     * 4. Store the last encrypted block to use as IV for next chunk
     *
     * @param dataStream Flow of byte arrays representing the encrypted data stream
     * @param key Decryption key
     * @param iv Initialization vector (16 bytes)
     * @return Flow of decrypted byte arrays
     */
    fun streamDecryptWithCbc(
            dataStream: Flow<ByteArray>,
            key: ByteArray,
            iv: ByteArray
    ): Flow<ByteArray> = channelFlow {
        require(key.isNotEmpty()) { "Key cannot be empty" }
        require(iv.size == BLOCK_SIZE) { "IV must be $BLOCK_SIZE bytes" }

        val aesKey = aes.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key)

        // Padding block filled with 16s (PKCS7 padding for a full block)
        val padding = ByteArray(BLOCK_SIZE) { BLOCK_SIZE.toByte() }

        var previousIv: ByteArray = iv
        var bufferedChunk: ByteArray? = null

        dataStream.collect { chunk ->
            // Process the buffered chunk (if any) - this is NOT the last chunk
            bufferedChunk?.let { prevChunk ->
                val cipher = aesKey.cipher()

                // Create artificial padding using the chunk's last block as IV
                val paddingIv = prevChunk.copyOfRange(prevChunk.size - BLOCK_SIZE, prevChunk.size)
                val encryptedPadding =
                        cipher.encryptWithIv(paddingIv, padding).copyOfRange(0, BLOCK_SIZE)

                // Decrypt with artificial padding - PKCS7 will strip the artificial padding
                val decrypted =
                        cipher.decryptWithIv(
                                previousIv,
                                ByteArrayUtil.combine(prevChunk, encryptedPadding)
                        )

                // Update IV for next chunk (CBC chaining)
                previousIv = prevChunk.copyOfRange(prevChunk.size - BLOCK_SIZE, prevChunk.size)

                send(decrypted)
            }

            // Buffer the current chunk for next iteration
            bufferedChunk = chunk
        }

        // Process the last buffered chunk WITHOUT artificial padding
        // This lets the library naturally handle the real PKCS7 padding
        bufferedChunk?.let { lastChunk ->
            val cipher = aesKey.cipher()
            val decrypted = cipher.decryptWithIv(previousIv, lastChunk)
            send(decrypted)
        }
    }

    /** Stream decrypt data with AES-CBC using SecureByteArray key. */
    fun streamDecryptWithCbc(
            dataStream: Flow<ByteArray>,
            key: SecureByteArray,
            iv: ByteArray
    ): Flow<ByteArray> = streamDecryptWithCbc(dataStream, key.toByteArray(), iv)
}
