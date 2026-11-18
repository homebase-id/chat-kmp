package id.homebase.api.video

import id.homebase.api.file.FileOperationsProvider
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

// Walks the top-level boxes by their headers alone, so a moov after the media costs no pass over it. Reads to
// the end of the file rather than trusting its reported size, which a content:// provider may give as 0. Reads in
// windows because each range read may reopen the file, which is slow for a content:// URI.
internal suspend fun readMoov(fileOps: FileOperationsProvider, path: String): ByteArray? {
    val size = fileOps.getFileSize(path).takeIf { it > 0 } ?: Long.MAX_VALUE
    var window = ByteArray(0)
    var windowAt = 0L
    var at = 0L
    while (at + 8 <= size) {
        if (at + 16 > windowAt + window.size) {
            window = fileOps.readFileRange(path, at, MOOV_READ_WINDOW)
            windowAt = at
        }
        val i = (at - windowAt).toInt()
        if (i + 8 > window.size) return null
        var boxSize = readU32(window, i, little = false).toLong() and 0xFFFFFFFFL
        var headerSize = 8
        if (boxSize == 1L && i + 16 <= window.size) {
            headerSize = 16
            boxSize = readU64(window, i + 8)
        } else if (boxSize == 0L) {
            boxSize = size - at
        }
        if (boxSize < headerSize) return null
        if (fourCc(window, i + 4) == "moov") {
            if (boxSize > MAX_MOOV_BYTES) return null
            val end = i + boxSize.toInt()
            return if (end <= window.size) window.copyOfRange(i + headerSize, end) else fileOps.readFileRange(path, at + headerSize, (boxSize - headerSize).toInt())
        }
        at += boxSize
    }
    return null
}

// Holds a camera's moov whole: about 90 KB for five minutes of 1080p.
private const val MOOV_READ_WINDOW = 256 * 1024
