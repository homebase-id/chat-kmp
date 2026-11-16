package id.homebase.api.video

import id.homebase.api.image.fourCc
import id.homebase.api.image.readU32
import id.homebase.api.image.readU64

/** An MP4 box: its type and where its body lies in the bytes it was read from. */
internal class Mp4Box(val type: String, val start: Int, val end: Int)

/** The boxes in `b[from, until)`, stopping at the first that doesn't fit. */
internal fun mp4Boxes(b: ByteArray, from: Int, until: Int): List<Mp4Box> {
    val boxes = mutableListOf<Mp4Box>()
    var i = from
    while (i + 8 <= until) {
        var size = readU32(b, i, little = false).toLong() and 0xFFFFFFFFL
        var header = 8
        if (size == 1L && i + 16 <= until) {
            size = readU64(b, i + 8)
            header = 16
        }
        if (size < header || i + size > until) break
        boxes += Mp4Box(fourCc(b, i + 4), i + header, (i + size).toInt())
        i += size.toInt()
    }
    return boxes
}

/** A long recording's sample tables run to a few MB; past this the file isn't one a camera or encoder wrote. */
internal const val MAX_MOOV_BYTES = 64L * 1024 * 1024
