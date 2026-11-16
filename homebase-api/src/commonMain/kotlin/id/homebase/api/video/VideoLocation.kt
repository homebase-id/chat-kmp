package id.homebase.api.video

import id.homebase.api.image.fourCc
import id.homebase.api.image.readU16
import id.homebase.api.image.readU32
import id.homebase.api.image.readU64

/** Where a video was filmed, in degrees and metres, as its camera recorded it. */
data class VideoLocation(val latitude: Double, val longitude: Double, val altitude: Double? = null) {
    companion object {
        private val ISO_6709 = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)?""")

        /** An ISO 6709 point in decimal degrees, "+37.7749-122.4194+010.000/", as cameras write it. */
        fun parse(iso6709: String): VideoLocation? {
            val match = ISO_6709.find(iso6709.trim()) ?: return null
            val (lat, lon, alt) = match.destructured
            val latitude = lat.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
            val longitude = lon.toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return null
            return VideoLocation(latitude, longitude, alt.toDoubleOrNull())
        }
    }
}

/**
 * Reads an MP4 or QuickTime file's recorded location from its bytes as they stream past, for an app that
 * keeps metadata where the uploaded copy can't hold it (an HLS stream has no place for it). Feed every
 * chunk in order, then read [location]. Only the `moov` box is held; the media data is counted past.
 *
 * Reads Apple's `com.apple.quicktime.location.ISO6709` key and the `©xyz` atom Android and ffmpeg write.
 */
class VideoLocationReader {
    private val header = ByteArray(16)
    private var headerFill = 0
    private var skipping = 0L
    private var moov: ByteArray? = null
    private var moovFill = 0
    private var done = false

    var location: VideoLocation? = null
        private set

    /** Nothing more to learn: the location is found, or the file can't hold one where it's looked for. */
    val finished: Boolean get() = done

    /** The bytes [feed] would only count past next. A caller that can seek skips them instead. */
    fun takeSkip(): Long = skipping.also { skipping = 0 }

    fun feed(chunk: ByteArray) {
        var at = 0
        while (at < chunk.size && !done) {
            val moovBuffer = moov
            when {
                skipping > 0 -> {
                    val n = minOf(skipping, (chunk.size - at).toLong()).toInt()
                    skipping -= n
                    at += n
                }
                moovBuffer != null -> {
                    val n = minOf(moovBuffer.size - moovFill, chunk.size - at)
                    chunk.copyInto(moovBuffer, moovFill, at, at + n)
                    moovFill += n
                    at += n
                    if (moovFill == moovBuffer.size) {
                        location = locationInMoov(moovBuffer)
                        done = true
                    }
                }
                else -> {
                    header[headerFill++] = chunk[at++]
                    readBoxHeader()
                }
            }
        }
    }

    // Called a byte at a time until a whole top-level box header is in hand.
    private fun readBoxHeader() {
        if (headerFill < 8) return
        val size32 = readU32(header, 0, little = false).toLong() and 0xFFFFFFFFL
        val headerSize = if (size32 == 1L) 16 else 8
        if (headerFill < headerSize) return
        val size = if (size32 == 1L) readU64(header, 8) else size32
        val type = fourCc(header, 4)
        headerFill = 0
        // Size 0 runs to the end of the file: nothing after it to find.
        if (size == 0L || size < headerSize) {
            done = true
            return
        }
        val body = size - headerSize
        if (type == "moov") {
            if (body > MAX_MOOV_BYTES) done = true else moov = ByteArray(body.toInt())
        } else {
            skipping = body
        }
    }

    private fun locationInMoov(moov: ByteArray): VideoLocation? {
        val top = mp4Boxes(moov, 0, moov.size)
        top.firstOrNull { it.type == "meta" }?.let { metadataKeyLocation(moov, it) }?.let { return it }
        val udta = top.firstOrNull { it.type == "udta" } ?: return null
        val xyz = mp4Boxes(moov, udta.start, udta.end).firstOrNull { it.type == "©xyz" } ?: return null
        // A 16-bit text length and a 16-bit language code, then the text.
        if (xyz.start + 4 > xyz.end) return null
        val length = readU16(moov, xyz.start, little = false)
        val textEnd = minOf(xyz.start + 4 + length, xyz.end)
        return VideoLocation.parse(moov.decodeToString(xyz.start + 4, textEnd))
    }

    // QuickTime metadata: `keys` names each entry, and `ilst` holds the values under 1-based key indexes.
    private fun metadataKeyLocation(b: ByteArray, meta: Mp4Box): VideoLocation? {
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
        return VideoLocation.parse(b.decodeToString(data.start + 8, data.end))
    }

    private companion object {
        const val APPLE_LOCATION_KEY = "com.apple.quicktime.location.ISO6709"
    }
}
