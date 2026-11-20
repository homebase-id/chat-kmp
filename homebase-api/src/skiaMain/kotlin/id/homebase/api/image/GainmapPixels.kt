package id.homebase.api.image

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** A gain map's raw 8-bit pixels, one channel or BGRA, as a platform decoder hands them over (an HEIC's auxiliary image). */
internal class GainmapPixels(val bytes: ByteArray, val width: Int, val height: Int, val rowBytes: Int, val channels: Int) {
    /**
     * An [HdrGainmap] with each value through [lut] when there is one, turned upright with the photo's EXIF
     * [orientation] (the map is stored as the sensor saw it). Null when the sizes don't add up.
     */
    fun toGainmap(metadata: GainmapMetadata, orientation: Int, lut: ByteArray?): HdrGainmap? {
        if (width <= 0 || height <= 0 || channels !in setOf(1, 4) || rowBytes < width * channels || bytes.size < rowBytes.toLong() * height) return null
        val table = lut ?: IDENTITY
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) {
            var from = y * rowBytes
            var to = y * width * 4
            repeat(width) {
                if (channels == 1) {
                    val v = table[bytes[from].toInt() and 0xFF]
                    rgba[to] = v; rgba[to + 1] = v; rgba[to + 2] = v
                } else {
                    rgba[to] = table[bytes[from + 2].toInt() and 0xFF]
                    rgba[to + 1] = table[bytes[from + 1].toInt() and 0xFF]
                    rgba[to + 2] = table[bytes[from].toInt() and 0xFF]
                }
                rgba[to + 3] = -1
                from += channels
                to += 4
            }
        }
        // Untagged, so the values go in as data; through a JPEG so the orientation and decode match a JPEG gain map's.
        val image = Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE, null), rgba, width * 4)
        val encoded = try {
            image.encodeToData(EncodedImageFormat.JPEG, QUALITY)
        } finally {
            image.close()
        } ?: return null
        val jpeg = try {
            encoded.bytes
        } finally {
            encoded.close()
        }
        val upright = if (orientation == 1) jpeg else ImageMetadataScrubber.withOrientation(jpeg, orientation)
        return HdrGainmap(openGainmapSource(upright), metadata)
    }

    private companion object {
        val IDENTITY = ByteArray(256) { it.toByte() }

        // Near-lossless: the map is decoded again and rendered at the output's size and quality.
        const val QUALITY = 98
    }
}
