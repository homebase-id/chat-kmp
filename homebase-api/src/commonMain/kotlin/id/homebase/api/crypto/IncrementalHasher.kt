package id.homebase.api.crypto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/** Pure Kotlin because wasmJs WebCrypto can only hash a whole buffer, asynchronously. */
interface IncrementalHasher {
    fun update(input: ByteArray, offset: Int = 0, length: Int = input.size - offset)

    /** Finishes the hash; the hasher can't be used after this. */
    fun digest(): ByteArray
}

fun Flow<ByteArray>.hashedBy(hasher: IncrementalHasher?): Flow<ByteArray> =
    if (hasher == null) this else onEach { hasher.update(it) }

/** SHA-256's initial hash value, which BLAKE3 also uses as its IV. */
internal val SHA256_INITIAL_STATE = intArrayOf(
    0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6,
    0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
)
