package id.homebase.api.crypto

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadHash
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.file.FileOperationsProvider
import kotlin.io.encoding.Base64

private const val PAYLOAD_HASH_LENGTH = 32
private val CONTENT_HASH_IV_MASK = ByteArray(16) { 0xAA.toByte() }

fun PayloadHashAlgorithm.newHasher(): IncrementalHasher = when (this) {
    PayloadHashAlgorithm.Sha256 -> Sha256Hasher()
    PayloadHashAlgorithm.Blake3 -> Blake3Hasher()
    PayloadHashAlgorithm.Unknown -> error("No hasher for an unknown payload hash algorithm")
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
    algorithm = algorithm,
    storedHash = Base64.encode(storedDigest),
    contentHash = Base64.encode(AesCbc.encrypt(contentDigest, payloadKey.aesKey, contentHashIv(payloadKey.iv))),
)

/**
 * The plaintext hash; [payloadKey] is null for an unencrypted payload. Null when unreadable: an
 * unknown algorithm, or a contentHash that doesn't decrypt to a hash.
 */
suspend fun PayloadHash.plaintextContentHash(payloadKey: KeyHeader?): ByteArray? {
    if (algorithm == PayloadHashAlgorithm.Unknown) return null
    val raw = runCatching { Base64.decode(contentHash) }.getOrNull() ?: return null
    val plain = if (payloadKey == null) raw else {
        runCatching { AesCbc.decrypt(raw, payloadKey.aesKey, contentHashIv(payloadKey.iv)) }.getOrNull() ?: return null
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

/** Upload the result with this same [payloadKey] IV: the encrypted content hash is bound to it. */
suspend fun FileOperationsProvider.encryptWithPayloadHash(
    inputPath: String,
    outputPath: String,
    payloadKey: KeyHeader,
    algorithm: PayloadHashAlgorithm,
): PayloadHash {
    val contentHasher = algorithm.newHasher()
    val storedHasher = algorithm.newHasher()
    encryptFileStreaming(inputPath, outputPath, payloadKey, contentHasher, storedHasher)
    return encryptedPayloadHash(algorithm, storedHasher.digest(), contentHasher.digest(), payloadKey)
}
