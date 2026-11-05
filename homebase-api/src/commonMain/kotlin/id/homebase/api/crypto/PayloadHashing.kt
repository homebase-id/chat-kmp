package id.homebase.api.crypto

import id.homebase.api.serialization.Base64Bytes
import id.homebase.api.client.KeyHeader
import id.homebase.api.common.SecureByteArray
import id.homebase.api.client.drives.files.PayloadHash
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.file.FileOperationsProvider
import kotlinx.coroutines.CancellationException

private const val PAYLOAD_HASH_LENGTH = 32
private val CONTENT_HASH_IV_MASK = ByteArray(16) { 0xAA.toByte() }

fun PayloadHashAlgorithm.newHasher(): IncrementalHasher = when (this) {
    PayloadHashAlgorithm.Sha256 -> Sha256Hasher()
    PayloadHashAlgorithm.Blake3 -> Blake3Hasher()
}

/** XOR with a non-zero mask, so the encrypted content hash never reuses the payload IV. */
fun contentHashIv(payloadIv: ByteArray): ByteArray = ByteArrayUtil.equiByteArrayXor(payloadIv, CONTENT_HASH_IV_MASK)

/** [payloadKey] holds the file's AES key and the payload's IV. */
suspend fun encryptedPayloadHash(
    algorithm: PayloadHashAlgorithm,
    storedDigest: ByteArray,
    contentDigest: ByteArray,
    payloadKey: KeyHeader,
): PayloadHash = PayloadHash(
    algorithm = algorithm.wireName,
    storedHash = Base64Bytes(storedDigest),
    contentHash = Base64Bytes(AesCbc.encrypt(contentDigest, payloadKey.aesKey, contentHashIv(payloadKey.iv))),
)

/**
 * The plaintext hash; [payloadKey] is null for an unencrypted payload. Null when unreadable: an
 * unknown algorithm, or a contentHash that doesn't decrypt to a hash.
 */
suspend fun PayloadHash.plaintextContentHash(payloadKey: KeyHeader?): ByteArray? {
    if (knownAlgorithm == null) return null
    val raw = contentHash?.bytes ?: return null
    val plain = if (payloadKey == null) raw else {
        try {
            AesCbc.decrypt(raw, payloadKey.aesKey, contentHashIv(payloadKey.iv))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // On wasmJs a failed WebCrypto decrypt is a bare Throwable, not an Exception.
            return null
        }
    }
    return plain.takeIf { it.size == PAYLOAD_HASH_LENGTH }
}

/**
 * Encrypts [inputPath] into [outputPath] in one streaming pass and deletes a partial output on
 * failure. The hashers, when given, see the plaintext going in and the ciphertext coming out.
 */
suspend fun FileOperationsProvider.encryptFileStreaming(
    inputPath: String,
    outputPath: String,
    keyHeader: KeyHeader,
    plaintextHasher: IncrementalHasher? = null,
    ciphertextHasher: IncrementalHasher? = null,
) {
    try {
        writeStream(
            path = outputPath,
            data = AesCbc.streamEncryptWithCbc(
                dataStream = readFileAsFlow(inputPath).hashedBy(plaintextHasher),
                key = keyHeader.aesKey,
                iv = keyHeader.iv,
            ).hashedBy(ciphertextHasher),
        )
    } catch (t: Throwable) {
        runCatching { deleteTempFile(outputPath) }
        throw t
    }
}

/**
 * [iv] is the fresh payload IV the upload must send: the encrypted content hash is bound to it.
 * [plaintextBytes] is what was actually read and encrypted, whatever the platform reported for the source.
 */
class HashedPayload(val hash: PayloadHash, val iv: ByteArray, val plaintextBytes: Long)

suspend fun FileOperationsProvider.encryptWithPayloadHash(
    inputPath: String,
    outputPath: String,
    aesKey: SecureByteArray,
    algorithm: PayloadHashAlgorithm,
): HashedPayload {
    val payloadKey = KeyHeader(ByteArrayUtil.getRndByteArray(16), aesKey)
    val contentHasher = algorithm.newHasher()
    val storedHasher = algorithm.newHasher()
    var plaintextBytes = 0L
    val countingContentHasher = object : IncrementalHasher by contentHasher {
        override fun update(input: ByteArray, offset: Int, length: Int) {
            plaintextBytes += length
            contentHasher.update(input, offset, length)
        }
    }
    encryptFileStreaming(inputPath, outputPath, payloadKey, countingContentHasher, storedHasher)
    val hash = encryptedPayloadHash(algorithm, storedHasher.digest(), contentHasher.digest(), payloadKey)
    return HashedPayload(hash, payloadKey.iv, plaintextBytes)
}
