@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package id.homebase.api.util

import kotlin.wasm.unsafe.withScopedMemoryAllocator

/*
 * Bulk byte bridge to JS, replacing the Base64 idiom (~6x the payload in peak memory plus a
 * synchronous per-byte `charCodeAt` loop on the main thread).
 *
 * Kotlin/Wasm is Wasm GC, so a ByteArray is a GC `(array i8)` with no address in `memory.buffer`
 * and JS cannot view one directly — the Emscripten `HEAPU8.subarray()` trick does not apply here.
 * `kotlin.wasm.unsafe` linear memory does have addresses, the module exports it as `memory`, and
 * the compiler puts `wasmExports` in scope for `js()` bodies precisely so they can reach it
 * (`compose.resources` moves its own bytes the same way). So bytes go through a staging window:
 * an in-wasm store loop fills it, one typed-array view hands the window to JS.
 *
 * The window is fixed rather than payload-sized because linear memory never shrinks — staging a
 * whole clip would grow the tab's heap by the clip size for the rest of the session.
 *
 * Not kotlinx-browser's `ByteArray.toInt8Array()`: that is an element-wise loop, so it costs one
 * wasm->JS interop call per byte.
 */
private const val STAGING_WINDOW_BYTES = 1 shl 20

/** Blob rather than a Uint8Array: the bytes land off the JS heap and may spill to disk. */
fun ByteArray.toJsBlob(mimeType: String): JsAny {
    val parts = newJsArray()
    stageThroughLinearMemory { address, _, length -> appendBlobPart(parts, address, length) }
    return blobFromParts(parts, mimeType)
}

/** The caller owns the revoke. */
fun ByteArray.toBlobObjectUrl(mimeType: String): String = objectUrlFromBlob(toJsBlob(mimeType))

fun ByteArray.toJsUint8Array(): JsAny {
    val sink = newUint8Array(size)
    stageThroughLinearMemory { address, offset, length ->
        copyIntoUint8Array(sink, offset, address, length)
    }
    return sink
}

fun jsUint8ArrayToByteArray(source: JsAny): ByteArray {
    val total = uint8ArrayLength(source)
    val bytes = ByteArray(total)
    if (total == 0) return bytes
    withScopedMemoryAllocator { allocator ->
        val window = minOf(STAGING_WINDOW_BYTES, total)
        val base = allocator.allocate(window)
        val address = base.address.toInt()
        var offset = 0
        while (offset < total) {
            val length = minOf(window, total - offset)
            copyFromUint8Array(source, offset, address, length)
            for (i in 0 until length) bytes[offset + i] = (base + i).loadByte()
            offset += length
        }
    }
    return bytes
}

private inline fun ByteArray.stageThroughLinearMemory(
    crossinline emit: (address: Int, offset: Int, length: Int) -> Unit,
) {
    if (isEmpty()) return
    val source = this
    withScopedMemoryAllocator { allocator ->
        val window = minOf(STAGING_WINDOW_BYTES, source.size)
        val base = allocator.allocate(window)
        val address = base.address.toInt()
        var offset = 0
        while (offset < source.size) {
            val length = minOf(window, source.size - offset)
            for (i in 0 until length) (base + i).storeByte(source[offset + i])
            emit(address, offset, length)
            offset += length
        }
    }
}

// Every body re-reads `wasmExports.memory.buffer`; growing linear memory detaches the previous
// ArrayBuffer, so a cached view would silently become unusable.

private fun newJsArray(): JsAny = js("[]")

private fun appendBlobPart(parts: JsAny, address: Int, length: Int): Unit = js(
    "{ parts.push(new Blob([new Uint8Array(wasmExports.memory.buffer, address, length)])); }"
)

private fun blobFromParts(parts: JsAny, mimeType: String): JsAny =
    js("new Blob(parts, { type: mimeType })")

private fun objectUrlFromBlob(blob: JsAny): String = js("URL.createObjectURL(blob)")

private fun newUint8Array(size: Int): JsAny = js("new Uint8Array(size)")

private fun copyIntoUint8Array(sink: JsAny, offset: Int, address: Int, length: Int): Unit =
    js("{ sink.set(new Uint8Array(wasmExports.memory.buffer, address, length), offset); }")

private fun uint8ArrayLength(source: JsAny): Int = js("source.length")

private fun copyFromUint8Array(source: JsAny, offset: Int, address: Int, length: Int): Unit = js(
    """{
        new Uint8Array(wasmExports.memory.buffer, address, length)
            .set(source.subarray(offset, offset + length));
    }"""
)
