package id.homebase.api.crypto

/** The IEEE CRC-32 that ZIP uses, despite the name; pass the last result as [initial] to continue over more data. */
object Crc32c {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun calculateCrc32c(initial: UInt = 0u, data: ByteArray): UInt {
        var crc = initial.toInt().inv()
        for (byte in data) crc = table[(crc xor byte.toInt()) and 0xFF] xor (crc ushr 8)
        return crc.inv().toUInt()
    }
}
