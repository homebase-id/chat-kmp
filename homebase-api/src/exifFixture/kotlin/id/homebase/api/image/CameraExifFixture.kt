package id.homebase.api.image

/**
 * A camera's EXIF TIFF block: IFD0 with Make "Canon", Model "Canon EOS R5" and Orientation 6, pointing at an Exif IFD
 * with FNumber 18/10, ExposureTime 1/250, FocalLength 50/1, ISO 400 and OffsetTimeOriginal "+09:00".
 * - [manualLens]: FNumber and FocalLength 0/1, as a lens that can't tell the camera writes them.
 * - [brokenFocalLength]: FocalLength 50/0.
 * - [isoTwice]: ISO as two values, 400 and 400, as the standard allows; [isoThrice], three, too many to sit inline.
 * - [exifPointerType]: the Exif IFD pointer's type, LONG (4) or IFD (13).
 */
internal object CameraExifFixture {
    const val MAKE = "Canon"
    const val MODEL = "Canon EOS R5"
    const val OFFSET = "+09:00"

    // Header 8, IFD0 at 8 with 4 entries (2 + 48 + 4 = 54), the Exif IFD at 62 with 5 (2 + 60 + 4 = 66), then the values.
    const val MAKE_AT = 8 + 54 + 66

    fun tiff(
        littleEndian: Boolean,
        manualLens: Boolean = false,
        brokenFocalLength: Boolean = false,
        isoTwice: Boolean = false,
        isoThrice: Boolean = false,
        exifPointerType: Int = 4,
    ): ByteArray {
        fun u16(v: Int) = if (littleEndian) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        fun u32(v: Int) = if (littleEndian) ByteArray(4) { (v ushr (8 * it)).toByte() } else ByteArray(4) { (v ushr (8 * (3 - it))).toByte() }
        fun entry(tag: Int, type: Int, count: Int, value: ByteArray) = u16(tag) + u16(type) + u32(count) + value.copyOf(4)
        fun ascii(text: String) = text.encodeToByteArray() + 0.toByte()

        val make = ascii(MAKE)
        val model = ascii(MODEL)
        val offset = ascii(OFFSET)
        val exifAt = 8 + 54
        val modelAt = MAKE_AT + make.size
        val offsetAt = modelAt + model.size
        val fNumberAt = offsetAt + offset.size
        val exposureAt = fNumberAt + 8
        val focalAt = exposureAt + 8
        val isosAt = focalAt + 8
        val ifd0 = u16(4) +
            entry(0x010F, 2, make.size, u32(MAKE_AT)) +
            entry(0x0110, 2, model.size, u32(modelAt)) +
            entry(0x0112, 3, 1, u16(6)) +
            entry(0x8769, exifPointerType, 1, u32(exifAt)) +
            u32(0)
        val exif = u16(5) +
            entry(0x829A, 5, 1, u32(exposureAt)) +
            entry(0x829D, 5, 1, u32(fNumberAt)) +
            (if (isoThrice) entry(0x8827, 3, 3, u32(isosAt)) else entry(0x8827, 3, if (isoTwice) 2 else 1, u16(400) + u16(if (isoTwice) 400 else 0))) +
            entry(0x9011, 2, offset.size, u32(offsetAt)) +
            entry(0x920A, 5, 1, u32(focalAt)) +
            u32(0)
        val header = (if (littleEndian) "II" else "MM").encodeToByteArray() + u16(42) + u32(8)
        val fNumber = if (manualLens) u32(0) + u32(1) else u32(18) + u32(10)
        val focal = u32(if (manualLens) 0 else 50) + u32(if (brokenFocalLength) 0 else 1)
        val isos = if (isoThrice) u16(400) + u16(400) + u16(400) else ByteArray(0)
        return header + ifd0 + exif + make + model + offset + fNumber + u32(1) + u32(250) + focal + isos
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
