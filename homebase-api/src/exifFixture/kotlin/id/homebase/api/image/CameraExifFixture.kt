package id.homebase.api.image

/**
 * A camera's EXIF TIFF block: IFD0 with ImageDescription [DESCRIPTION], Make "Canon", Model "Canon EOS R5", Orientation
 * 6, Software "Lightroom 7.1", DateTime "2026:01:16 08:00:00", Artist [ARTIST] and Copyright [COPYRIGHT], pointing at an
 * Exif IFD with FNumber 18/10, ExposureTime 1/250, FocalLength 50/1, ISO 400, DateTimeOriginal "2026:01:15 15:00:00",
 * OffsetTimeOriginal "+09:00", ExposureProgram 2, ExposureBias -7/10, SubjectDistance 25/10, MeteringMode 5, Flash 0x10,
 * UserComment "ASCII" [COMMENT], ColorSpace 1, CustomRendered 1, WhiteBalance 0, DigitalZoomRatio 2/1,
 * FocalLengthIn35mmFilm 75, SceneCaptureType 3, BodySerialNumber, LensMake, LensModel and LensSerialNumber, and at a GPS
 * IFD with TimeStamp 6/1 0/1 30/1, SpeedRef "K", Speed 36/1, ImgDirection 1234/10, DateStamp "2026:01:15" and
 * HPositioningError 5/1.
 * - [manualLens]: FNumber and FocalLength 0/1, as a lens that can't tell the camera writes them.
 * - [brokenFocalLength]: FocalLength 50/0.
 * - [isoTwice]: ISO as two values, 400 and 400, as the standard allows; [isoThrice], three, too many to sit inline.
 * - [exifPointerType]: the Exif IFD pointer's type, LONG (4) or IFD (13).
 * - [plainTimes]: DateTimeDigitized and OffsetTime in place of the two originals, as some editors leave them.
 * - [unicodeComment]: the comment as "UNICODE", UTF-16 in the block's byte order.
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
    const val DESCRIPTION = "Shibuya at dusk"
    const val ARTIST = "Jane Doe"
    const val COPYRIGHT = "Copyright 2026 Jane Doe"
    const val BODY_SERIAL = "032024001234"
    const val LENS_SERIAL = "9400012345"
    const val COMMENT = "Taken from the bridge"
    private const val GPS_DATE = "2026:01:15"

    // Header 8; IFD0 at 8 with 10 entries (2 + 120 + 4 = 126); the Exif IFD at 134 with 22 (2 + 264 + 4 = 270); the GPS
    // IFD at 404 with 6 (2 + 72 + 4 = 78); then the values.
    const val EXIF_AT = 8 + 126
    const val GPS_AT = EXIF_AT + 270
    const val MAKE_AT = GPS_AT + 78

    // Where an Exif IFD entry's count is, by its place in the IFD; its inline value is 4 on.
    fun exifCountAt(index: Int) = EXIF_AT + 2 + 12 * index + 4

    fun tiff(
        littleEndian: Boolean,
        manualLens: Boolean = false,
        brokenFocalLength: Boolean = false,
        isoTwice: Boolean = false,
        isoThrice: Boolean = false,
        exifPointerType: Int = 4,
        plainTimes: Boolean = false,
        unicodeComment: Boolean = false,
        commentText: String = COMMENT,
    ): ByteArray {
        fun u16(v: Int) = if (littleEndian) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        fun u32(v: Int) = if (littleEndian) ByteArray(4) { (v ushr (8 * it)).toByte() } else ByteArray(4) { (v ushr (8 * (3 - it))).toByte() }
        fun entry(tag: Int, type: Int, count: Int, value: ByteArray) = u16(tag) + u16(type) + u32(count) + value.copyOf(4)
        fun ascii(text: String) = text.encodeToByteArray() + 0.toByte()
        fun rational(numerator: Int, denominator: Int) = u32(numerator) + u32(denominator)

        val strings = listOf(MAKE, MODEL, OFFSET, TAKEN, CHANGED, SOFTWARE, LENS_MAKE, LENS_MODEL, DESCRIPTION, ARTIST, COPYRIGHT, BODY_SERIAL, LENS_SERIAL, GPS_DATE)
            .map(::ascii)
        val at = strings.runningFold(MAKE_AT) { offset, bytes -> offset + bytes.size }
        fun text(tag: Int, i: Int) = entry(tag, 2, strings[i].size, u32(at[i]))

        val fNumberAt = at[strings.size]
        val exposureAt = fNumberAt + 8
        val focalAt = exposureAt + 8
        val biasAt = focalAt + 8
        val directionAt = biasAt + 8
        val errorAt = directionAt + 8
        val distanceAt = errorAt + 8
        val zoomAt = distanceAt + 8
        val speedAt = zoomAt + 8
        val gpsTimeAt = speedAt + 8
        val commentAt = gpsTimeAt + 24
        val comment = if (unicodeComment) {
            "UNICODE".encodeToByteArray() + 0.toByte() + commentText.flatMap { u16(it.code).toList() }.toByteArray()
        } else {
            "ASCII".encodeToByteArray() + ByteArray(3) + commentText.encodeToByteArray()
        }
        val isosAt = commentAt + comment.size

        val ifd0 = u16(10) +
            text(0x010E, 8) +
            text(0x010F, 0) +
            text(0x0110, 1) +
            entry(0x0112, 3, 1, u16(6)) +
            text(0x0131, 5) +
            text(0x0132, 4) +
            text(0x013B, 9) +
            text(0x8298, 10) +
            entry(0x8769, exifPointerType, 1, u32(EXIF_AT)) +
            entry(0x8825, 4, 1, u32(GPS_AT)) +
            u32(0)
        val exif = u16(22) +
            entry(0x829A, 5, 1, u32(exposureAt)) +
            entry(0x829D, 5, 1, u32(fNumberAt)) +
            entry(0x8822, 3, 1, u16(2)) +
            (if (isoThrice) entry(0x8827, 3, 3, u32(isosAt)) else entry(0x8827, 3, if (isoTwice) 2 else 1, u16(400) + u16(if (isoTwice) 400 else 0))) +
            text(if (plainTimes) 0x9004 else 0x9003, 3) +
            text(if (plainTimes) 0x9010 else 0x9011, 2) +
            entry(0x9204, 10, 1, u32(biasAt)) +
            entry(0x9206, 5, 1, u32(distanceAt)) +
            entry(0x9207, 3, 1, u16(5)) +
            entry(0x9209, 3, 1, u16(0x10)) +
            entry(0x920A, 5, 1, u32(focalAt)) +
            entry(0x9286, 7, comment.size, u32(commentAt)) +
            entry(0xA001, 3, 1, u16(1)) +
            entry(0xA401, 3, 1, u16(1)) +
            entry(0xA403, 3, 1, u16(0)) +
            entry(0xA404, 5, 1, u32(zoomAt)) +
            entry(0xA405, 3, 1, u16(75)) +
            entry(0xA406, 3, 1, u16(3)) +
            text(0xA431, 11) +
            text(0xA433, 6) +
            text(0xA434, 7) +
            text(0xA435, 12) +
            u32(0)
        val gps = u16(6) +
            entry(0x0007, 5, 3, u32(gpsTimeAt)) +
            entry(0x000C, 2, 2, "K".encodeToByteArray() + 0.toByte()) +
            entry(0x000D, 5, 1, u32(speedAt)) +
            entry(0x0011, 5, 1, u32(directionAt)) +
            text(0x001D, 13) +
            entry(0x001F, 5, 1, u32(errorAt)) +
            u32(0)
        val header = (if (littleEndian) "II" else "MM").encodeToByteArray() + u16(42) + u32(8)
        val fNumber = if (manualLens) rational(0, 1) else rational(18, 10)
        val focal = rational(if (manualLens) 0 else 50, if (brokenFocalLength) 0 else 1)
        val isos = if (isoThrice) u16(400) + u16(400) + u16(400) else ByteArray(0)
        val values = strings.reduce(ByteArray::plus) + fNumber + rational(1, 250) + focal + rational(-7, 10) + rational(1234, 10) +
            rational(5, 1) + rational(25, 10) + rational(2, 1) + rational(36, 1) + rational(6, 1) + rational(0, 1) + rational(30, 1) + comment
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
