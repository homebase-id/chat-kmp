package id.homebase.api.image

// Byte readers and writers shared by the image container parsers in this package.

internal fun readU16(b: ByteArray, at: Int, little: Boolean): Int {
    val a = b[at].toInt() and 0xFF
    val c = b[at + 1].toInt() and 0xFF
    return if (little) (c shl 8) or a else (a shl 8) or c
}

/** Unclamped: a value with the top bit set comes back negative, which callers' bounds checks reject. */
internal fun readU32(b: ByteArray, at: Int, little: Boolean): Int =
    if (little) (readU16(b, at + 2, true) shl 16) or readU16(b, at, true)
    else (readU16(b, at, false) shl 16) or readU16(b, at + 2, false)

internal fun readU64(b: ByteArray, at: Int): Long =
    ((readU32(b, at, little = false).toLong() and 0xFFFFFFFFL) shl 32) or (readU32(b, at + 4, little = false).toLong() and 0xFFFFFFFFL)

internal fun writeLe32(b: ByteArray, at: Int, value: Int) {
    for (i in 0..3) b[at + i] = (value ushr (8 * i)).toByte()
}

internal fun fourCc(b: ByteArray, at: Int): String = buildString {
    for (k in 0 until 4) append((b[at + k].toInt() and 0xFF).toChar())
}

/** A RIFF chunk: FourCC, little-endian size, then [data] padded to an even length. */
internal fun webpChunk(type: String, data: ByteArray): ByteArray {
    val out = ByteArray(8 + data.size + (data.size and 1))
    type.encodeToByteArray().copyInto(out)
    writeLe32(out, 4, data.size)
    data.copyInto(out, 8)
    return out
}
