package id.homebase.api.crypto

import kotlin.uuid.Uuid

/**
 * Pure-Kotlin MD5 implementation for deterministic GUID derivation (e.g. uniqueId from odinId).
 * This is NOT used for cryptographic hashing — production code uses SHA-256 via HashUtil.
 * MD5 exists here only to match the wire format of existing TypeScript clients that derive
 * contact uniqueIds with `toGuidId(odinId) = md5(odinId)` so both clients converge on the
 * same 16-byte identifier.
 */
object Md5 {

    fun hash(input: ByteArray): ByteArray {
        val msgLenBits = input.size.toLong() * 8

        val padLen = if (input.size % 64 < 56) 56 - input.size % 64 else 120 - input.size % 64
        val padded = ByteArray(input.size + padLen + 8)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 8 + i] = ((msgLenBits ushr (8 * i)) and 0xFF).toByte()
        }

        var a = 0x67452301
        var b = -0x10325477
        var c = -0x67452302
        var d = 0x10325476

        val m = IntArray(16)
        var offset = 0
        while (offset < padded.size) {
            for (j in 0 until 16) {
                m[j] = (padded[offset + j * 4].toInt() and 0xFF) or
                        ((padded[offset + j * 4 + 1].toInt() and 0xFF) shl 8) or
                        ((padded[offset + j * 4 + 2].toInt() and 0xFF) shl 16) or
                        ((padded[offset + j * 4 + 3].toInt() and 0xFF) shl 24)
            }

            val aa = a
            val bb = b
            val cc = c
            val dd = d

            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when {
                    i < 16 -> { f = (b and c) or (b.inv() and d); g = i }
                    i < 32 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    i < 48 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                val temp = d
                d = c
                c = b
                val sum = a + f + K[i] + m[g]
                b += sum.rotateLeft(S[i])
                a = temp
            }

            a += aa
            b += bb
            c += cc
            d += dd

            offset += 64
        }

        return intsToLittleEndianBytes(a, b, c, d)
    }

    /**
     * Mirrors TypeScript `toGuidId(input)` — md5(utf8(input)) interpreted as a 16-byte UUID.
     * Input is lowercased to match how TS odinIds are usually normalized, but the TS helper
     * itself does not lowercase; callers that care about case should pass the value as-is.
     */
    fun toGuidId(input: String): Uuid {
        return Uuid.fromByteArray(hash(input.encodeToByteArray()))
    }

    private fun intsToLittleEndianBytes(vararg ints: Int): ByteArray {
        val out = ByteArray(ints.size * 4)
        for (i in ints.indices) {
            out[i * 4] = (ints[i] and 0xFF).toByte()
            out[i * 4 + 1] = ((ints[i] ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((ints[i] ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((ints[i] ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
    )

    private val K = intArrayOf(
        -0x28955b88, -0x173848aa, 0x242070db, -0x3e423112,
        -0x0a83f051, 0x4787c62a, -0x57cfb9ed, -0x02b96aff,
        0x698098d8, -0x74bb0851, -0x0000a44f, -0x76a32842,
        0x6b901122, -0x02678e6d, -0x5986bc72, 0x49b40821,
        -0x09e1da9e, -0x3fbf4cc0, 0x265e5a51, -0x16493856,
        -0x29d0efa3, 0x02441453, -0x275e197f, -0x182c0438,
        0x21e1cde6, -0x3cc8f82a, -0x0b2af279, 0x455a14ed,
        -0x561c16fb, -0x03105c08, 0x676f02d9, -0x72d5b376,
        -0x0005c6be, -0x788e097f, 0x6d9d6122, -0x021ac7f4,
        -0x5b4115bc, 0x4bdecfa9, -0x0944b4a0, -0x41404390,
        0x289b7ec6, -0x155ed806, -0x2b10cf7b, 0x04881d05,
        -0x262b2fc7, -0x1924661b, 0x1fa27cf8, -0x3b53a99b,
        -0x0bd6ddbc, 0x432aff97, -0x546bdc59, -0x036c5fc7,
        0x655b59c3, -0x70f3336e, -0x00100b83, -0x7a7ba22f,
        0x6fa87e4f, -0x01d31920, -0x5cfebcec, 0x4e0811a1,
        -0x08ac817e, -0x42c50dcb, 0x2ad7d2bb, -0x14792c6f
    )
}
