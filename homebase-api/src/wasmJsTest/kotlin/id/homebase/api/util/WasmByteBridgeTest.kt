@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package id.homebase.api.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

// Spans several staging windows and ends mid-window, so a wrong offset or a dropped tail shows up.
private const val MULTI_WINDOW_SIZE = (1 shl 20) * 2 + 12_345

private fun aperiodicBytes(size: Int) = ByteArray(size) { (it xor (it shr 7) xor (it shr 17)).toByte() }

private fun jsBlobSize(blob: JsAny): Int = js("blob.size")

class WasmByteBridgeTest {

    @Test
    fun roundTripsAcrossStagingWindows() {
        val bytes = aperiodicBytes(MULTI_WINDOW_SIZE)
        assertContentEquals(bytes, jsUint8ArrayToByteArray(bytes.toJsUint8Array()))
    }

    // Uint8Array is unsigned and Kotlin's Byte is signed; 0x80..0xFF is where that goes wrong.
    @Test
    fun roundTripsEveryByteValue() {
        val bytes = ByteArray(256) { (it - 128).toByte() }
        assertContentEquals(bytes, jsUint8ArrayToByteArray(bytes.toJsUint8Array()))
    }

    @Test
    fun roundTripsEmpty() {
        assertContentEquals(ByteArray(0), jsUint8ArrayToByteArray(ByteArray(0).toJsUint8Array()))
    }

    @Test
    fun blobCarriesEveryStagedWindow() {
        val bytes = aperiodicBytes(MULTI_WINDOW_SIZE)
        assertEquals(MULTI_WINDOW_SIZE, jsBlobSize(bytes.toJsBlob("application/octet-stream")))
    }
}
