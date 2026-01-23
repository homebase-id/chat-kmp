package id.homebase.homebasekmppoc.prototype.lib.crypto

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.uuid.Uuid

/** Cryptographic hashing utilities using cryptography-kotlin */
object HashUtil {
    const val SHA256_ALGORITHM = "SHA-256"

    private val crypto = CryptographyProvider.Default
    private val sha256Algo = crypto.get(SHA256)
    private val hkdfAlgo = crypto.get(HKDF.Companion)

    /** Compute SHA-256 hash of input */
    suspend fun sha256(input: ByteArray): ByteArray {
        return sha256Algo.hasher().hash(input)
    }

    /** Compute SHA-256 hash of a stream with optional nonce Returns hash and stream length */
    suspend fun streamSha256(
            inputStream: Source,
            optionalNonce: ByteArray? = null
    ): Pair<ByteArray, Long> {
        // Read all data from the stream
        val streamData = inputStream.readByteArray()
        val totalBytes = streamData.size.toLong()

        // Combine nonce (if provided) with stream data
        val dataToHash =
                if (optionalNonce != null) {
                    optionalNonce + streamData
                } else {
                    streamData
                }

        // Hash the combined data
        val hash = sha256Algo.hasher().hash(dataToHash)

        return Pair(hash, totalBytes)
    }

    /** HKDF (HMAC-based Extract-and-Expand Key Derivation Function) using SHA-256 RFC 5869 */
    suspend fun hkdf(sharedEccSecret: ByteArray, salt: ByteArray, outputKeySize: Int): ByteArray {
        require(outputKeySize >= 16) { "Output key size cannot be less than 16" }

        // Use cryptography-kotlin HKDF with SHA-256
        val derivation =
                hkdfAlgo.secretDerivation(
                        digest = SHA256,
                        outputSize = outputKeySize.bytes,
                        salt = salt,
                        info = null // No additional info in our use case
                )

        return derivation.deriveSecretToByteArray(sharedEccSecret)
    }

    /** Reduce a SHA-256 hash to 16 bytes by taking first 16 bytes */
    private suspend fun reduceSha256Hash(data: ByteArray): ByteArray {
        val hash = sha256(data)
        return hash.copyOf(16)
    }

    /** XOR two byte arrays of equal length */
    private fun xorByteArrays(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == b.size) { "Arrays must be same length" }
        return ByteArray(a.size) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }
    }

    /** Convert a 16-byte array to a UUID */
    private fun byteArrayToUuid(bytes: ByteArray): Uuid {
        require(bytes.size >= 16) { "Need at least 16 bytes for UUID" }
        // Build UUID from bytes (RFC 4122 format)
        val hexString =
                bytes.take(16).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        val uuidString =
                "${hexString.substring(0, 8)}-${hexString.substring(8, 12)}-${hexString.substring(12, 16)}-${hexString.substring(16, 20)}-${hexString.substring(20, 32)}"
        return Uuid.parse(uuidString)
    }

    /**
     * Generates a deterministic conversation ID from two identity strings. Both strings are
     * lowercased, SHA256 hashed, reduced to 16 bytes, XOR'd together, then SHA256 hashed again and
     * converted to a UUID.
     *
     * This allows two users to independently compute the same conversation ID.
     *
     * @param a First identity string (e.g., logged-in user's identity)
     * @param b Second identity string (e.g., recipient's identity)
     * @return A deterministic UUID representing the conversation
     * @throws IllegalArgumentException if either string is empty
     */
    suspend fun getNewXorId(a: String, b: String): Uuid {
        require(a.isNotEmpty() && b.isNotEmpty()) { "Both strings must be non-empty" }

        val bufferA = reduceSha256Hash(a.lowercase().encodeToByteArray())
        val bufferB = reduceSha256Hash(b.lowercase().encodeToByteArray())

        val xorBuffer = xorByteArrays(bufferA, bufferB)

        val conversationIdHashReduced = reduceSha256Hash(xorBuffer)
        return byteArrayToUuid(conversationIdHashReduced)
    }
}
