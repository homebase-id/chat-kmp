package id.homebase.api.image

private const val ICC_FLAG = 0x20
private const val CHUNKS_START = 12

/**
 * [webp] without its ICC profile. Encoders tag sRGB too, which costs 456 bytes to say what an
 * untagged image already means. Done on the file because Android bitmaps can't be left untagged.
 */
internal fun stripWebpColorProfile(webp: ByteArray): ByteArray {
    if (webp.size <= CHUNKS_START || fourCc(webp, 0) != "RIFF" || fourCc(webp, 8) != "WEBP") return webp
    if (fourCc(webp, CHUNKS_START) != "VP8X") return webp

    val chunks = mutableListOf<IntRange>()
    var at = CHUNKS_START
    var hadProfile = false
    while (at + 8 <= webp.size) {
        val paddedSize = (readLe32(webp, at + 4) + 1) and 1.inv()
        if (paddedSize < 0 || paddedSize > webp.size - at - 8) return webp
        val end = at + 8 + paddedSize
        if (fourCc(webp, at) == "ICCP") hadProfile = true else chunks += at until end
        at = end
    }
    if (!hadProfile) return webp

    val flags = (webp[CHUNKS_START + 8].toInt() and 0xFF) and ICC_FLAG.inv()
    // With nothing left for the extended header to announce, the smaller plain form is valid.
    val kept = if (flags == 0) chunks.drop(1) else chunks
    val out = ByteArray(CHUNKS_START + kept.sumOf { it.last - it.first + 1 })
    webp.copyInto(out, 0, 0, CHUNKS_START)
    writeLe32(out, 4, out.size - 8)
    var write = CHUNKS_START
    for (chunk in kept) {
        webp.copyInto(out, write, chunk.first, chunk.last + 1)
        write += chunk.last - chunk.first + 1
    }
    if (flags != 0) out[CHUNKS_START + 8] = flags.toByte()
    return out
}

private fun fourCc(bytes: ByteArray, at: Int) = bytes.decodeToString(at, at + 4)

private fun readLe32(bytes: ByteArray, at: Int): Int =
    (0..3).sumOf { (bytes[at + it].toInt() and 0xFF) shl (8 * it) }

private fun writeLe32(bytes: ByteArray, at: Int, value: Int) {
    for (i in 0..3) bytes[at + i] = (value ushr (8 * i)).toByte()
}
