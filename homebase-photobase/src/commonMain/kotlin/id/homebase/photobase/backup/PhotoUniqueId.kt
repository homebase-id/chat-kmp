package id.homebase.photobase.backup

import id.homebase.api.crypto.Blake3Hasher
import kotlin.uuid.Uuid

/**
 * Never change this scheme: a different result re-uploads every backed-up asset.
 * A Live Photo passes the still's digest, then the paired video's.
 */
object PhotoUniqueId {
    private val SCHEME = "homebase-photobase-unique-id-v1".encodeToByteArray()
    private const val DIGEST_BYTES = 32
    private const val UUID_BYTES = 16

    fun fromContentDigests(digests: List<ByteArray>): Uuid {
        require(digests.isNotEmpty()) { "an asset has at least one part" }
        val hasher = Blake3Hasher()
        hasher.update(SCHEME)
        for (digest in digests) {
            require(digest.size == DIGEST_BYTES) { "BLAKE3 digest expected, got ${digest.size} bytes" }
            hasher.update(digest)
        }
        return Uuid.fromByteArray(hasher.digest().copyOf(UUID_BYTES))
    }
}
