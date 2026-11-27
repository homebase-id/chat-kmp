package id.homebase.api.image

/**
 * A camera's EXIF TIFF block: IFD0 with Make "Canon", Model "Canon EOS R5" and Orientation 6, pointing at an Exif IFD
 * with FNumber 18/10, ExposureTime 1/250, FocalLength 50/1 and ISO 400. With [manualLens], FNumber and FocalLength
 * are 0/1, as a lens that can't tell the camera writes them.
 */
internal object CameraExifFixture {
    const val MAKE = "Canon"
    const val MODEL = "Canon EOS R5"

    fun tiff(littleEndian: Boolean, manualLens: Boolean = false): ByteArray {
        fun u16(v: Int) = if (littleEndian) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        fun u32(v: Int) = if (littleEndian) ByteArray(4) { (v ushr (8 * it)).toByte() } else ByteArray(4) { (v ushr (8 * (3 - it))).toByte() }
        fun entry(tag: Int, type: Int, count: Int, value: ByteArray) = u16(tag) + u16(type) + u32(count) + value.copyOf(4)
        fun short(v: Int) = u16(v) + byteArrayOf(0, 0)

        val make = MAKE.encodeToByteArray() + 0.toByte()
        val model = MODEL.encodeToByteArray() + 0.toByte()
        // Header 8, IFD0 at 8 with 4 entries (2 + 48 + 4 = 54), Exif IFD at 62 with 4 entries (54), then the values.
        val exifAt = 8 + 54
        val valuesAt = exifAt + 54
        val makeAt = valuesAt
        val modelAt = makeAt + make.size
        val fNumberAt = modelAt + model.size
        val exposureAt = fNumberAt + 8
        val focalAt = exposureAt + 8
        val ifd0 = u16(4) +
            entry(0x010F, 2, make.size, u32(makeAt)) +
            entry(0x0110, 2, model.size, u32(modelAt)) +
            entry(0x0112, 3, 1, short(6)) +
            entry(0x8769, 4, 1, u32(exifAt)) +
            u32(0)
        val exif = u16(4) +
            entry(0x829A, 5, 1, u32(exposureAt)) +
            entry(0x829D, 5, 1, u32(fNumberAt)) +
            entry(0x8827, 3, 1, short(400)) +
            entry(0x920A, 5, 1, u32(focalAt)) +
            u32(0)
        val header = (if (littleEndian) "II" else "MM").encodeToByteArray() + u16(42) + u32(8)
        return header + ifd0 + exif + make + model + u32(if (manualLens) 0 else 18) + u32(if (manualLens) 1 else 10) + u32(1) + u32(250) + u32(if (manualLens) 0 else 50) + u32(1)
    }

    /** [tiff] as the EXIF APP1 of [jpeg], right after its SOI. */
    fun inJpeg(tiff: ByteArray, jpeg: ByteArray = MINIMAL_JPEG): ByteArray {
        val body = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val length = body.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + body
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    // SOI, then EOI: enough for a reader that walks the segments, not for a decoder.
    private val MINIMAL_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
}
