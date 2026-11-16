package id.homebase.api.crypto

import id.homebase.blake3.NativeBlake3
import java.nio.ByteBuffer

internal actual fun platformBlake3Hasher(): IncrementalHasher? = if (nativeLoads) NativeBlake3Hasher() else null

// Robolectric host tests run the Android code on a desktop JVM, without the library.
private val nativeLoads: Boolean by lazy { runCatching { NativeBlake3.stateSize() }.isSuccess }

/** The official C BLAKE3, NEON on 64-bit ARM: many times faster than the Kotlin port on a phone. */
private class NativeBlake3Hasher : IncrementalHasher {
    private val state = ByteBuffer.allocateDirect(STATE_SIZE).also(NativeBlake3::init)
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

    private companion object {
        val STATE_SIZE = NativeBlake3.stateSize()
    }
}
