package id.homebase.chat.services

import io.ktor.utils.io.core.toByteArray
import kotlin.uuid.Uuid

expect fun sha256(input: ByteArray): ByteArray

object XorIdUtil {

    fun getNewXorId(a: String, b: String): Uuid {
        require(a.isNotBlank() && b.isNotBlank()) { "Both strings must be non-empty" }

        val bufferA = reduceSha256Hash(a.lowercase().toByteArray())
        val bufferB = reduceSha256Hash(b.lowercase().toByteArray())

        val xorBuffer = xorByteArrays(bufferA, bufferB)
        val finalHash = reduceSha256Hash(xorBuffer)

        return Uuid.fromByteArray(finalHash)
    }


    fun reduceSha256Hash(input: ByteArray): ByteArray {
        val digest = sha256(input)

        require(digest.size == 32)

        return ByteArray(16) { i ->
            ((digest[i].toInt() xor digest[i + 16].toInt()) and 0xFF).toByte()
        }
    }


    private fun xorByteArrays(a: ByteArray, b: ByteArray): ByteArray {
        val max = maxOf(a.size, b.size)
        return ByteArray(max) { i ->
            ((a.getOrNull(i)?.toInt() ?: 0) xor (b.getOrNull(i)?.toInt() ?: 0)).toByte()
        }
    }
}
