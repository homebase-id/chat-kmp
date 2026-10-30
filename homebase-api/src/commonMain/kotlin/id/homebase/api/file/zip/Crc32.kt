package id.homebase.api.file.zip

/** CRC-32 as ZIP uses it (IEEE 802.3, reflected). Not [id.homebase.api.crypto.Crc32c], which is another polynomial. */
class Crc32 {
    private var crc = -1

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        var c = crc
        for (i in offset until offset + length) {
            c = TABLE[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        crc = c
    }

    val value: Long get() = (crc.inv().toLong()) and 0xFFFFFFFFL

    private companion object {
        val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }
    }
}
