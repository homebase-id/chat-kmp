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

        finalHash[6] = ((finalHash[6].toInt() and 0x0F) or 0x40).toByte()
        finalHash[8] = ((finalHash[8].toInt() and 0x3F) or 0x80).toByte()

        return Uuid.fromByteArray(finalHash)
    }


    fun reduceSha256Hash(input: ByteArray): ByteArray {
        val digest = sha256(input)

        require(digest.size == 32)

        return ByteArray(16) { i ->
            ((digest[i].toInt() xor digest[i + 16].toInt()) and 0xFF).toByte()
        }
    }

    fun xorUuidV4(a: Uuid, b: Uuid): Uuid {
        val aBytes = a.toByteArray()
        val bBytes = b.toByteArray()

        val result = ByteArray(16) { i ->
            (aBytes[i].toInt() xor bBytes[i].toInt()).toByte()
        }

        // enforce UUID v4 version
        result[6] = ((result[6].toInt() and 0x0F) or 0x40).toByte()

        // enforce RFC-4122 variant
        result[8] = ((result[8].toInt() and 0x3F) or 0x80).toByte()

        return Uuid.fromByteArray(result)
    }

    private fun xorByteArrays(a: ByteArray, b: ByteArray): ByteArray {
        val max = maxOf(a.size, b.size)
        return ByteArray(max) { i ->
            ((a.getOrNull(i)?.toInt() ?: 0) xor (b.getOrNull(i)?.toInt() ?: 0)).toByte()
        }
    }
}
