package id.homebase.api.crypto

import co.touchlab.kermit.Logger
import id.homebase.blake3.NativeBlake3
import java.nio.ByteBuffer

internal actual fun platformBlake3Hasher(): IncrementalHasher? = stateSize?.let(::NativeBlake3Hasher)

// Null where the library doesn't load: Robolectric host tests run the Android code on a desktop JVM.
private val stateSize: Int? by lazy {
    runCatching { NativeBlake3.stateSize() }
        .onFailure { Logger.w(tag = "Blake3", throwable = it) { "native BLAKE3 didn't load; hashing in Kotlin, about 20x slower" } }
        .getOrNull()
}

/** The official C BLAKE3, NEON on 64-bit ARM: many times faster than the Kotlin port on a phone. */
private class NativeBlake3Hasher(stateSize: Int) : IncrementalHasher {
    private val state = ByteBuffer.allocateDirect(stateSize).also(NativeBlake3::init)
    private var finished = false

    override fun update(input: ByteArray, offset: Int, length: Int) {
        check(!finished) { "digest() was already called" }
        require(offset >= 0 && length >= 0 && offset + length <= input.size) { "range $offset+$length of ${input.size}" }
        if (length > 0) NativeBlake3.update(state, input, offset, length)
    }

    override fun digest(): ByteArray {
        check(!finished) { "digest() was already called" }
        finished = true
        return ByteArray(32).also { NativeBlake3.finish(state, it) }
    }
}
