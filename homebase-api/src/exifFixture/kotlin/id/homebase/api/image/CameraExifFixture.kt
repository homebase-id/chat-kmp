package id.homebase.api.image

/**
 * A camera's EXIF TIFF block: IFD0 with Make "Canon", Model "Canon EOS R5", Orientation 6, Software "Lightroom 7.1" and
 * DateTime "2026:01:16 08:00:00", pointing at an Exif IFD with FNumber 18/10, ExposureTime 1/250, FocalLength 50/1,
 * ISO 400, DateTimeOriginal "2026:01:15 15:00:00", OffsetTimeOriginal "+09:00", ExposureProgram 2, ExposureBias -7/10,
 * MeteringMode 5, Flash 0x10, WhiteBalance 0, LensMake "Canon" and LensModel "RF50mm F1.8 STM", and at a GPS IFD with
 * ImgDirection 1234/10 and HPositioningError 5/1.
 * - [manualLens]: FNumber and FocalLength 0/1, as a lens that can't tell the camera writes them.
 * - [brokenFocalLength]: FocalLength 50/0.
 * - [isoTwice]: ISO as two values, 400 and 400, as the standard allows; [isoThrice], three, too many to sit inline.
 * - [exifPointerType]: the Exif IFD pointer's type, LONG (4) or IFD (13).
 * - [plainTimes]: DateTimeDigitized and OffsetTime in place of the two originals, as some editors leave them.
 */
internal object CameraExifFixture {
    const val MAKE = "Canon"
    const val MODEL = "Canon EOS R5"
    const val OFFSET = "+09:00"
    const val TAKEN = "2026:01:15 15:00:00"
    const val CHANGED = "2026:01:16 08:00:00"
    const val SOFTWARE = "Lightroom 7.1"
    const val LENS_MAKE = "Canon"
    const val LENS_MODEL = "RF50mm F1.8 STM"

    // Header 8; IFD0 at 8 with 7 entries (2 + 84 + 4 = 90); the Exif IFD at 98 with 13 (2 + 156 + 4 = 162); the GPS IFD
    // at 260 with 2 (2 + 24 + 4 = 30); then the values.
    const val EXIF_AT = 8 + 90
    const val GPS_AT = EXIF_AT + 162
    const val MAKE_AT = GPS_AT + 30

    // Where an Exif IFD entry's count is, by its place in the IFD.
    fun exifCountAt(index: Int) = EXIF_AT + 2 + 12 * index + 4

    fun tiff(
        littleEndian: Boolean,
        manualLens: Boolean = false,
        brokenFocalLength: Boolean = false,
        isoTwice: Boolean = false,
        isoThrice: Boolean = false,
        exifPointerType: Int = 4,
        plainTimes: Boolean = false,
    ): ByteArray {
        fun u16(v: Int) = if (littleEndian) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        fun u32(v: Int) = if (littleEndian) ByteArray(4) { (v ushr (8 * it)).toByte() } else ByteArray(4) { (v ushr (8 * (3 - it))).toByte() }
        fun entry(tag: Int, type: Int, count: Int, value: ByteArray) = u16(tag) + u16(type) + u32(count) + value.copyOf(4)
        fun ascii(text: String) = text.encodeToByteArray() + 0.toByte()

        val strings = listOf(MAKE, MODEL, OFFSET, TAKEN, CHANGED, SOFTWARE, LENS_MAKE, LENS_MODEL).map(::ascii)
        val at = strings.runningFold(MAKE_AT) { offset, bytes -> offset + bytes.size }
        val (makeAt, modelAt, offsetAt, takenAt, changedAt) = at
        val softwareAt = at[5]
        val lensMakeAt = at[6]
        val lensModelAt = at[7]
        val fNumberAt = at[8]
        val exposureAt = fNumberAt + 8
        val focalAt = exposureAt + 8
        val biasAt = focalAt + 8
        val directionAt = biasAt + 8
        val errorAt = directionAt + 8
        val isosAt = errorAt + 8
        fun text(tag: Int, i: Int, offset: Int) = entry(tag, 2, strings[i].size, u32(offset))

        val ifd0 = u16(7) +
            text(0x010F, 0, makeAt) +
            text(0x0110, 1, modelAt) +
            entry(0x0112, 3, 1, u16(6)) +
            text(0x0131, 5, softwareAt) +
            text(0x0132, 4, changedAt) +
            entry(0x8769, exifPointerType, 1, u32(EXIF_AT)) +
            entry(0x8825, 4, 1, u32(GPS_AT)) +
            u32(0)
        val exif = u16(13) +
            entry(0x829A, 5, 1, u32(exposureAt)) +
            entry(0x829D, 5, 1, u32(fNumberAt)) +
            entry(0x8822, 3, 1, u16(2)) +
            (if (isoThrice) entry(0x8827, 3, 3, u32(isosAt)) else entry(0x8827, 3, if (isoTwice) 2 else 1, u16(400) + u16(if (isoTwice) 400 else 0))) +
            text(if (plainTimes) 0x9004 else 0x9003, 3, takenAt) +
            text(if (plainTimes) 0x9010 else 0x9011, 2, offsetAt) +
            entry(0x9204, 10, 1, u32(biasAt)) +
            entry(0x9207, 3, 1, u16(5)) +
            entry(0x9209, 3, 1, u16(0x10)) +
            entry(0x920A, 5, 1, u32(focalAt)) +
            entry(0xA403, 3, 1, u16(0)) +
            text(0xA433, 6, lensMakeAt) +
            text(0xA434, 7, lensModelAt) +
            u32(0)
        val gps = u16(2) +
            entry(0x0011, 5, 1, u32(directionAt)) +
            entry(0x001F, 5, 1, u32(errorAt)) +
            u32(0)
        val header = (if (littleEndian) "II" else "MM").encodeToByteArray() + u16(42) + u32(8)
        val fNumber = if (manualLens) u32(0) + u32(1) else u32(18) + u32(10)
        val focal = u32(if (manualLens) 0 else 50) + u32(if (brokenFocalLength) 0 else 1)
        val isos = if (isoThrice) u16(400) + u16(400) + u16(400) else ByteArray(0)
        val values = strings.reduce(ByteArray::plus) + fNumber + u32(1) + u32(250) + focal + u32(-7) + u32(10) + u32(1234) + u32(10) + u32(5) + u32(1)
        return header + ifd0 + exif + gps + values + isos
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
