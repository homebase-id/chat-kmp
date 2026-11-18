package id.homebase.api.crypto

import co.touchlab.kermit.Logger
import id.homebase.blake3.NativeBlake3
import java.nio.ByteBuffer

internal actual fun platformBlake3Hasher(): IncrementalHasher? = stateSize?.let(::NativeBlake3Hasher)

// Null where the library doesn't load (Robolectric runs Android code on a desktop JVM), or where it hashes
// differently from the Kotlin port: no test on CI runs the native code, and a wrong hash fails every upload.
private val stateSize: Int? by lazy {
    val size = try {
        NativeBlake3.stateSize()
    } catch (e: LinkageError) {
        Logger.e(tag = "Blake3", throwable = e) { "native BLAKE3 didn't load; hashing in Kotlin, about 20x slower" }
        return@lazy null
    }
    // Across BLAKE3's 1024-byte chunk boundary and its first tree merge.
    val probe = ByteArray(2048 + 7) { (it % 251).toByte() }
    val native = NativeBlake3Hasher(size).apply { update(probe, 3, probe.size - 3) }.digest()
    val portable = PortableBlake3().apply { update(probe, 3, probe.size - 3) }.digest()
    size.takeIf { native.contentEquals(portable) }
        ?: null.also { Logger.e(tag = "Blake3") { "native BLAKE3 disagrees with the Kotlin port; hashing in Kotlin" } }
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
