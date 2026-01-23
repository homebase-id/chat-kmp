package id.homebase.homebasekmppoc.prototype.lib.crypto

import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * Utility functions for byte array operations
 */
object ByteArrayUtil {

    fun uInt32ToBytes(i: UInt): ByteArray {
        return byteArrayOf(
            ((i shr 24) and 0xFFu).toByte(),
            ((i shr 16) and 0xFFu).toByte(),
            ((i shr 8) and 0xFFu).toByte(),
            (i and 0xFFu).toByte()
        )
    }

    fun uInt64ToBytes(i: ULong): ByteArray {
        return byteArrayOf(
            ((i shr 56) and 0xFFu).toByte(),
            ((i shr 48) and 0xFFu).toByte(),
            ((i shr 40) and 0xFFu).toByte(),
            ((i shr 32) and 0xFFu).toByte(),
            ((i shr 24) and 0xFFu).toByte(),
            ((i shr 16) and 0xFFu).toByte(),
            ((i shr 8) and 0xFFu).toByte(),
            (i and 0xFFu).toByte()
        )
    }

    fun int8ToBytes(i: Byte): ByteArray {
        return byteArrayOf(i)
    }

    fun int16ToBytes(i: Short): ByteArray {
        return byteArrayOf(
            ((i.toInt() shr 8) and 0xFF).toByte(),
            (i.toInt() and 0xFF).toByte()
        )
    }

    fun int32ToBytes(i: Int): ByteArray {
        return byteArrayOf(
            ((i shr 24) and 0xFF).toByte(),
            ((i shr 16) and 0xFF).toByte(),
            ((i shr 8) and 0xFF).toByte(),
            (i and 0xFF).toByte()
        )
    }

    fun int64ToBytes(i: Long): ByteArray {
        return byteArrayOf(
            ((i shr 56) and 0xFF).toByte(),
            ((i shr 48) and 0xFF).toByte(),
            ((i shr 40) and 0xFF).toByte(),
            ((i shr 32) and 0xFF).toByte(),
            ((i shr 24) and 0xFF).toByte(),
            ((i shr 16) and 0xFF).toByte(),
            ((i shr 8) and 0xFF).toByte(),
            (i and 0xFF).toByte()
        )
    }

    fun bytesToInt64(bytes: ByteArray): Long {
        require(bytes.size == 8) { "Input byte array must have exactly 8 elements." }

        return ((bytes[0].toLong() and 0xFF) shl 56) or
                ((bytes[1].toLong() and 0xFF) shl 48) or
                ((bytes[2].toLong() and 0xFF) shl 40) or
                ((bytes[3].toLong() and 0xFF) shl 32) or
                ((bytes[4].toLong() and 0xFF) shl 24) or
                ((bytes[5].toLong() and 0xFF) shl 16) or
                ((bytes[6].toLong() and 0xFF) shl 8) or
                (bytes[7].toLong() and 0xFF)
    }

    fun bytesToInt32(bytes: ByteArray): Int {
        require(bytes.size == 4) { "Input byte array must have exactly 4 elements." }

        return ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
    }

    fun bytesToInt32_little_endian(bytes: ByteArray): Int {
        require(bytes.size == 4) { "Input byte array must have exactly 4 elements." }
        return (bytes[0].toInt() and 0xFF) or
                ((bytes[1].toInt() and 0xFF) shl 8) or
                ((bytes[2].toInt() and 0xFF) shl 16) or
                ((bytes[3].toInt() and 0xFF) shl 24)
    }


    fun bytesToInt16(bytes: ByteArray): Short {
        require(bytes.size == 2) { "Input byte array must have exactly 2 elements." }

        return (((bytes[0].toInt() and 0xFF) shl 8) or
                (bytes[1].toInt() and 0xFF)).toShort()
    }

    fun bytesToInt8(bytes: ByteArray): Byte {
        require(bytes.size == 1) { "Input byte array must have exactly 1 element." }

        return bytes[0]
    }

    /**
     * Concatenates any number of byte arrays in parameter order
     */
    fun combine(vararg arrays: ByteArray): ByteArray {
        val totalSize = arrays.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (array in arrays) {
            array.copyInto(result, offset)
            offset += array.size
        }
        return result
    }

    /**
     * Split a byte array into multiple parts based on the specified lengths
     */
    fun split(data: ByteArray, vararg lengths: Int): Array<ByteArray> {
        val totalLengths = lengths.sum()
        require(totalLengths == data.size) {
            "The sum of lengths does not match the data length."
        }

        val result = Array(lengths.size) { ByteArray(0) }
        var offset = 0

        for (i in lengths.indices) {
            result[i] = data.copyOfRange(offset, offset + lengths[i])
            offset += lengths[i]
        }

        return result
    }

    /**
     * Split into two parts
     */
    fun split2(data: ByteArray, len1: Int, len2: Int): Pair<ByteArray, ByteArray> {
        val part1 = data.copyOfRange(0, len1)
        val part2 = data.copyOfRange(len1, len1 + len2)
        return Pair(part1, part2)
    }

    /**
     * Split into three parts
     */
    fun split3(data: ByteArray, len1: Int, len2: Int, len3: Int): Triple<ByteArray, ByteArray, ByteArray> {
        val part1 = data.copyOfRange(0, len1)
        val part2 = data.copyOfRange(len1, len1 + len2)
        val part3 = data.copyOfRange(len1 + len2, len1 + len2 + len3)
        return Triple(part1, part2, part3)
    }

    /**
     * Calculates SHA256 hash of input
     */
    suspend fun calculateSha256Hash(input: ByteArray): ByteArray {
        return HashUtil.sha256(input)
    }

    /**
     * Reduces a SHA256 hash to 16 bytes (Guid size) by XORing the two halves
     */
    suspend fun reduceSha256Hash(input: String): Uuid {
        return Uuid.fromByteArray(reduceSha256Hash(input.encodeToByteArray()))
    }

    /**
     * Reduces a SHA256 hash to 16 bytes by XORing the two halves
     */
    suspend fun reduceSha256Hash(input: ByteArray): ByteArray {
        val bytes = calculateSha256Hash(input)
        val half = bytes.size / 2
        val (part1, part2) = split2(bytes, half, half)
        return equiByteArrayXor(part1, part2)
    }

    /**
     * Prints a byte array as a Kotlin array literal
     */
    fun printByteArray(bytes: ByteArray): String {
        if (bytes.isEmpty()) {
            return "byteArrayOf()"
        }
        return "byteArrayOf(" + bytes.joinToString(", ") + ")"
    }

    /**
     * Securely wipes a byte array by clearing its contents
     */
    fun wipeByteArray(b: ByteArray) {
        b.fill(0)
    }

    /**
     * Returns a cryptographically strong random Guid (UUID)
     */
    fun getRandomCryptoGuid(): Uuid {
        return Uuid.fromByteArray(getRndByteArray(16))
    }

    /**
     * Returns true if key is strong, false if it appears constructed or weak
     */
    fun isStrongKey(data: ByteArray): Boolean {
        if (data.size < 16) {
            return false
        }

        var j = 0

        // Keys like this are considered weak "nnnn mmmm oooo pppp"
        for (i in 0 until data.size / 4) {
            if ((data[j] != data[j + 1]) ||
                (data[j] != data[j + 2]) ||
                (data[j] != data[j + 3])) {
                return true
            }
            j += 4
        }

        if (data.size % 4 != 0) {
            // If the key is an odd size then let's just see if the last
            // bytes are the same as the byte before
            for (i in 0 until data.size % 4) {
                if (data[j - 1] != data[j + i]) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Generates a cryptographically safe array of random bytes
     */
    fun getRndByteArray(nCount: Int): ByteArray {
        return Random.Default.nextBytes(nCount)
    }

    /**
     * Check if two byte arrays of equal length are identical
     */
    fun equiByteArrayCompare(ba1: ByteArray, ba2: ByteArray): Boolean {
        return ba1.contentEquals(ba2)
    }

    /**
     * XOR the two byte arrays with each other. Requires the same length.
     */
    fun equiByteArrayXor(ba1: ByteArray, ba2: ByteArray): ByteArray {
        require(ba1.size == ba2.size) { "Byte arrays are not the same length" }

        val result = ByteArray(ba1.size)
        for (i in ba1.indices) {
            result[i] = (ba1[i].toInt() xor ba2[i].toInt()).toByte()
        }
        return result
    }

    /**
     * memcmp for two 16 byte arrays
     * Returns: 1 if b1 > b2; 0 if equal; -1 if b1 < b2
     */
    fun muidcmp(b1: ByteArray?, b2: ByteArray?): Int {
        if (b1 == null || b2 == null) {
            return when {
                b1 === b2 -> 0  // Reference compare
                b1 == null -> -1
                else -> 1
            }
        }

        require(b1.size == 16 && b2.size == 16) { "b1,b2 must be 16 bytes" }

        for (i in 0 until 16) {
            if (b1[i] == b2[i]) {
                continue
            }
            val b1Unsigned = b1[i].toInt() and 0xFF
            val b2Unsigned = b2[i].toInt() and 0xFF
            return if (b1Unsigned > b2Unsigned) 1 else -1
        }

        return 0
    }

    /**
     * memcmp for two Guids (UUIDs)
     * Returns: 1 if b1 > b2; 0 if equal; -1 if b1 < b2
     */
    fun muidcmp(b1: Uuid?, b2: Uuid?): Int {
        return muidcmp(b1?.toByteArray(), b2?.toByteArray())
    }
}

/**
 * Extension function to convert String to UTF-8 byte array
 */
fun String.toUtf8ByteArray(): ByteArray = this.encodeToByteArray()

/**
 * Extension function to convert byte array to UTF-8 string
 */
fun ByteArray.toStringFromUtf8Bytes(): String = this.decodeToString()
