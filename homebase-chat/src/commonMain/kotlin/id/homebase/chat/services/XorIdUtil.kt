package id.homebase.chat.services

import id.homebase.api.common.OdinId
import io.ktor.utils.io.core.toByteArray
import kotlin.uuid.Uuid

expect fun sha256(input: ByteArray): ByteArray

object XorIdUtil {

    /**
     * True when [messageGroupId] is the deterministic 1:1 conversation id between
     * [self] and [sender]. Use on a chat-message header to decide whether the
     * message lives in a 1:1 with the sender (vs. a group whose id is a random Uuid).
     *
     * Pass `senderOdinId` from the message's fileMetadata — NOT `originalAuthor`,
     * which is content provenance and may differ for forwarded messages.
     */
    fun isOneToOneWithSender(self: OdinId, sender: OdinId, messageGroupId: Uuid): Boolean =
        getNewXorId(self.domainName, sender.domainName) == messageGroupId

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

        // enforce UUID v4 versionthe
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
