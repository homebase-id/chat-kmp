package id.homebase.api.crypto

import id.homebase.blake3.blake3_hasher
import id.homebase.blake3.blake3_hasher_finalize
import id.homebase.blake3.blake3_hasher_init
import id.homebase.blake3.blake3_hasher_update
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

internal actual fun platformBlake3Hasher(): IncrementalHasher? = if (nativeAgrees) NativeBlake3Hasher() else null

private val nativeAgrees: Boolean by lazy { hashesAsThePort(::NativeBlake3Hasher) }

/** The official C BLAKE3 with NEON, from homebase-blake3 as on Android. */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
private class NativeBlake3Hasher : IncrementalHasher {
    private val state = nativeHeap.alloc<blake3_hasher>().also { blake3_hasher_init(it.ptr) }

    @Suppress("unused")
    private val cleaner = createCleaner(state) { nativeHeap.free(it) }
    private var finished = false

    override fun update(input: ByteArray, offset: Int, length: Int) {
        check(!finished) { "digest() was already called" }
        checkRange(input, offset, length)
        if (length > 0) input.usePinned { blake3_hasher_update(state.ptr, it.addressOf(offset), length.convert()) }
    }

    override fun digest(): ByteArray {
        check(!finished) { "digest() was already called" }
        finished = true
        val out = ByteArray(32)
        out.usePinned { blake3_hasher_finalize(state.ptr, it.addressOf(0).reinterpret(), out.size.convert()) }
        return out
    }
}
