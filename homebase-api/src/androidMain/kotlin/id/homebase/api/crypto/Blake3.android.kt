package id.homebase.api.crypto

import co.touchlab.kermit.Logger
import id.homebase.blake3.NativeBlake3
import java.nio.ByteBuffer

internal actual fun platformBlake3Hasher(): IncrementalHasher? = stateSize?.let(::NativeBlake3Hasher)

// Null where the library doesn't load (Robolectric runs Android code on a desktop JVM).
private val stateSize: Int? by lazy {
    val size = try {
        NativeBlake3.stateSize()
    } catch (e: LinkageError) {
        Logger.e(tag = "Blake3", throwable = e) { "native BLAKE3 didn't load; hashing in Kotlin, about 20x slower" }
        return@lazy null
    }
    size.takeIf { hashesAsThePort { NativeBlake3Hasher(size) } }
}

/** The official C BLAKE3 with NEON: many times faster than the Kotlin port on a phone. */
private class NativeBlake3Hasher(stateSize: Int) : IncrementalHasher {
    private val state = ByteBuffer.allocateDirect(stateSize).also(NativeBlake3::init)
    private var finished = false

    override fun update(input: ByteArray, offset: Int, length: Int) {
        check(!finished) { "digest() was already called" }
        checkRange(input, offset, length)
        if (length > 0) NativeBlake3.update(state, input, offset, length)
    }

    override fun digest(): ByteArray {
        check(!finished) { "digest() was already called" }
        finished = true
        return ByteArray(32).also { NativeBlake3.finish(state, it) }
    }
}
