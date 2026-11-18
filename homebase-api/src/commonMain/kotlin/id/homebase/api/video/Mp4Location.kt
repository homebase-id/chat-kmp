package id.homebase.api.video

import id.homebase.api.image.fourCc
import id.homebase.api.image.readU16
import id.homebase.api.image.readU32
import id.homebase.api.media.MediaLocation

private const val APPLE_LOCATION_KEY = "com.apple.quicktime.location.ISO6709"

/** The location recorded in a `moov` box's bytes, its header excluded. */
internal fun locationInMoov(moov: ByteArray): MediaLocation? {
    val top = mp4Boxes(moov, 0, moov.size)
    top.firstOrNull { it.type == "meta" }?.let { metadataKeyLocation(moov, it) }?.let { return it }
    val udta = top.firstOrNull { it.type == "udta" } ?: return null
    val xyz = mp4Boxes(moov, udta.start, udta.end).firstOrNull { it.type == "©xyz" } ?: return null
    // A 16-bit text length and a 16-bit language code, then the text.
    if (xyz.start + 4 > xyz.end) return null
    val length = readU16(moov, xyz.start, little = false)
    val textEnd = minOf(xyz.start + 4 + length, xyz.end)
    return MediaLocation.parse(moov.decodeToString(xyz.start + 4, textEnd))
}

// QuickTime metadata: `keys` names each entry, and `ilst` holds the values under 1-based key indexes.
private fun metadataKeyLocation(b: ByteArray, meta: Mp4Box): MediaLocation? {
    // ISO's meta is a full box with 4 bytes of version and flags; QuickTime's isn't.
    val first = if (meta.start + 8 <= meta.end && fourCc(b, meta.start + 4) in setOf("hdlr", "keys", "ilst")) meta.start else meta.start + 4
    val boxes = mp4Boxes(b, first, meta.end)
    val keys = boxes.firstOrNull { it.type == "keys" } ?: return null
    val ilst = boxes.firstOrNull { it.type == "ilst" } ?: return null
    if (keys.start + 8 > keys.end) return null
    val count = readU32(b, keys.start + 4, little = false)
    var i = keys.start + 8
    var index = 0
    for (k in 1..count) {
        if (i + 8 > keys.end) return null
        val size = readU32(b, i, little = false)
        if (size < 8 || i + size > keys.end) return null
        if (b.decodeToString(i + 8, i + size) == APPLE_LOCATION_KEY) {
            index = k
            break
        }
        i += size
    }
    if (index == 0) return null
    val item = mp4Boxes(b, ilst.start, ilst.end).firstOrNull { readU32(b, it.start - 4, little = false) == index } ?: return null
    val data = mp4Boxes(b, item.start, item.end).firstOrNull { it.type == "data" } ?: return null
    // A 4-byte type and a 4-byte locale, then the value.
    if (data.start + 8 > data.end) return null
    return MediaLocation.parse(b.decodeToString(data.start + 8, data.end))
}
