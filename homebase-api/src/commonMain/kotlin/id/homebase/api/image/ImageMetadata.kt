package id.homebase.api.image

import id.homebase.api.file.contentTypeFromBytes
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.media.MediaLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/**
 * EXIF / image-file metadata read from JPEG/HEIC/TIFF/PNG bytes.
 *
 * All fields are nullable — most images carry only a subset (e.g. screenshots
 * have no GPS, scans have no camera model). Callers should treat absent fields
 * as "unknown", not as "zero".
 *
 * Time semantics: EXIF stores capture time as the camera's wall-clock (no
 * timezone). [capturedAt] holds that wall-clock value verbatim. If the
 * camera also wrote an OffsetTimeOriginal tag (newer iPhones, recent DSLRs),
 * [captureUtcOffset] carries it so callers can build an [kotlinx.datetime.Instant];
 * otherwise it is null and there is no safe way to convert to absolute time.
 */
data class ImageMetadata(
    val capturedAt: LocalDateTime? = null,
    val captureUtcOffset: UtcOffset? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    /** EXIF orientation tag, 1..8. 1 means "normal". See TIFF 6.0 §F. */
    val orientation: Int? = null,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    /**
     * The aperture as its f-number (1.8 for f/1.8), the exposure, the focal length and the ISO: on every platform, null
     * unless finite and above 0, as a manual lens writes 0 and a broken rational x/0. An ISO of several values reads as
     * its first; the others of several are unknown.
     */
    val fNumber: Double? = null,
    val exposureTimeSeconds: Double? = null,
    val focalLengthMm: Double? = null,
    val iso: Int? = null,
    /**
     * Read alike on every platform: names trimmed, empty as null; the exposure bias in EV, any finite value; the
     * exposure program (1..8), metering mode (1..6 or 255) and white balance (0 auto, 1 manual) as their EXIF codes,
     * "not defined" and unknown codes as null; the flash as written (bit 0: it fired); [modifiedAt] from DateTime;
     * the direction the camera faced in degrees, 0 until 360; the GPS horizontal error in metres, 0 or more.
     */
    val lensMake: String? = null,
    val lensModel: String? = null,
    val exposureBiasEv: Double? = null,
    val exposureProgram: Int? = null,
    val meteringMode: Int? = null,
    val flash: Int? = null,
    val whiteBalance: Int? = null,
    val software: String? = null,
    val modifiedAt: LocalDateTime? = null,
    val imageDirectionDegrees: Double? = null,
    val horizontalErrorMeters: Double? = null,
    /**
     * Read alike on every platform: the 35 mm-equivalent focal length, above 0; the digital zoom ratio, above 0 (0 is
     * none); the scene capture type (0..3), custom rendering (0 or 1) and colour space (1 sRGB, 65535 uncalibrated) as
     * their EXIF codes, others as null; the subject distance in metres, above 0 and below 1000 km (0 is unknown, and
     * EXIF's "infinity", 0xFFFFFFFF over its denominator, lands past that); names as above; the user comment with its
     * character-code prefix taken off; [gpsTime] in UTC, only with both the GPS date and time; the speed in km/h, 0
     * or more, from km/h, mph or knots.
     */
    val focalLength35mm: Int? = null,
    val digitalZoomRatio: Double? = null,
    val sceneCaptureType: Int? = null,
    val customRendered: Int? = null,
    val colorSpace: Int? = null,
    val subjectDistanceMeters: Double? = null,
    val imageDescription: String? = null,
    val artist: String? = null,
    val copyright: String? = null,
    val userComment: String? = null,
    val bodySerial: String? = null,
    val lensSerial: String? = null,
    val gpsTime: Instant? = null,
    val speedKmh: Double? = null,
) {
    val location: MediaLocation?
        get() = if (latitude != null && longitude != null) MediaLocation(latitude, longitude, altitudeMeters) else null
}

/**
 * Read EXIF / image-file metadata from encoded image bytes (JPEG, HEIC, TIFF,
 * PNG, WebP — depending on platform support).
 *
 * Returns null only when the bytes cannot be parsed at all. If the image has
 * no metadata, an [ImageMetadata] with all-null fields is returned.
 *
 * Web reads a JPEG's EXIF ([readJpegExifCamera]): every field here but the GPS position and the pixel size, and null
 * for anything else, a JPEG without EXIF too, since browsers expose no EXIF reader.
 */
expect fun readImageMetadata(srcBytes: ByteArray): ImageMetadata?

/** [readImageMetadata] for the file at [path], reading only its header where the platform can. */
internal suspend fun readImageMetadata(path: String, fileOps: FileOperationsProvider): ImageMetadata? =
    withContext(Dispatchers.Default) {
        val file = openImageFile(path, fileOps) ?: return@withContext readImageMetadata(fileOps.readFileBytes(path))
        file.use { it.metadata() }
    }

/**
 * The upright size of the image at [path], the one its thumbnails will have, reading only the
 * file's header wherever the format allows. Throws on a file no decoder can read.
 */
suspend fun readImageSize(path: String, fileOps: FileOperationsProvider): ImageSize =
    withContext(Dispatchers.Default) {
        openImageFile(path, fileOps)?.use { return@withContext it.displayedSize }
        val header = fileOps.readFileHeaderBytes(path)
        // Only a JPEG's turn is read here; decoders also turn other formats (WebP) by their EXIF.
        if (contentTypeFromBytes(header) == "image/jpeg") {
            ImageHeaderParser.parse(header)?.let { stored ->
                val turned = readJpegExifOrientation(header)?.let(::jpegOrientationSwapsDimensions) == true
                return@withContext if (turned) ImageSize(stored.pixelHeight, stored.pixelWidth) else stored
            }
        }
        val whole = if (header.size < FileOperationsProvider.DEFAULT_HEADER_BYTES) header else fileOps.readFileBytes(path)
        ImageUtils.getNaturalSize(whole)
    }

/**
 * Parse an EXIF DateTimeOriginal string ("YYYY:MM:DD HH:MM:SS") into a
 * [LocalDateTime]. Returns null on malformed input.
 *
 * Shared because every platform actual needs to do the same conversion —
 * EXIF colon-separated dates aren't ISO-8601 and kotlinx-datetime won't
 * parse them directly.
 */
internal fun parseExifLocalDateTime(raw: String?): LocalDateTime? {
    if (raw.isNullOrBlank()) return null
    // Some cameras emit "0000:00:00 00:00:00" as a placeholder. Treat as null.
    if (raw.startsWith("0000")) return null
    return try {
        // "YYYY:MM:DD HH:MM:SS" -> "YYYY-MM-DDTHH:MM:SS"
        val iso = raw.trim().let {
            val sp = it.indexOf(' ')
            if (sp < 0) return null
            val date = it.substring(0, sp).replace(':', '-')
            val time = it.substring(sp + 1)
            "${date}T${time}"
        }
        LocalDateTime.parse(iso)
    } catch (_: Throwable) {
        null
    }
}

/**
 * Parse an EXIF OffsetTime string ("+HH:MM" or "-HH:MM") into a [UtcOffset].
 * Returns null on malformed input.
 */
internal fun parseExifUtcOffset(raw: String?): UtcOffset? {
    if (raw.isNullOrBlank()) return null
    return try {
        UtcOffset.parse(raw.trim())
    } catch (_: Throwable) {
        null
    }
}

// The rules for the fields of [ImageMetadata] after [ImageMetadata.iso], on every platform.
internal fun exifName(raw: String?): String? = raw?.substringBefore('\u0000')?.trim()?.takeIf { it.isNotEmpty() }
internal fun exifExposureBias(raw: Double?): Double? = raw?.takeIf { it.isFinite() }
internal fun exifExposureProgram(raw: Int?): Int? = raw?.takeIf { it in 1..8 }
internal fun exifMeteringMode(raw: Int?): Int? = raw?.takeIf { it in 1..6 || it == 255 }
internal fun exifWhiteBalance(raw: Int?): Int? = raw?.takeIf { it == 0 || it == 1 }
internal fun exifImageDirection(raw: Double?): Double? = raw?.takeIf { it.isFinite() && it >= 0 && it < 360 }
internal fun exifHorizontalError(raw: Double?): Double? = raw?.takeIf { it.isFinite() && it >= 0 }
internal fun exifFocalLength35mm(raw: Int?): Int? = raw?.takeIf { it > 0 }
internal fun exifDigitalZoom(raw: Double?): Double? = raw?.takeIf { it.isFinite() && it > 0 }
internal fun exifSceneCaptureType(raw: Int?): Int? = raw?.takeIf { it in 0..3 }
internal fun exifCustomRendered(raw: Int?): Int? = raw?.takeIf { it == 0 || it == 1 }
internal fun exifColorSpace(raw: Int?): Int? = raw?.takeIf { it == 1 || it == 65535 }
internal fun exifSubjectDistance(raw: Double?): Double? = raw?.takeIf { it.isFinite() && it > 0 && it < 1_000_000 }

/**
 * A UserComment's bytes: an 8-byte character code, then the text. [littleEndian] gives the TIFF's byte order, asked
 * for only by a UNICODE comment, so a reader that has to look it up does so only then; null guesses.
 */
internal fun exifUserComment(raw: ByteArray?, littleEndian: () -> Boolean? = { null }): String? {
    if (raw == null || raw.size < 8) return null
    val code = raw.copyOfRange(0, 8).decodeToString().trimEnd('\u0000', ' ')
    val text = raw.copyOfRange(8, raw.size)
    val decoded = when (code) {
        "UNICODE" -> utf16Text(text, littleEndian())
        // ASCII, JIS (rarely written, and not decoded here) and undefined: undefined is UTF-8 in practice.
        "JIS" -> return null
        else -> text.decodeToString()
    }
    return exifComment(decoded)
}

/** A comment the platform decoded itself, with the same trimming. */
internal fun exifComment(raw: String?): String? = raw?.replace("\u0000", "")?.trim()?.takeIf { it.isNotEmpty() }

// Without the TIFF's byte order (a HEIC, or a reader that doesn't say), a byte-order mark, else Latin text's zero bytes.
private fun utf16Text(text: ByteArray, littleEndian: Boolean?): String {
    val bom = if (text.size >= 2) (text[0].toInt() and 0xFF) to (text[1].toInt() and 0xFF) else null
    return when {
        bom == (0xFF to 0xFE) -> utf16(text.copyOfRange(2, text.size), littleEndian = true)
        bom == (0xFE to 0xFF) -> utf16(text.copyOfRange(2, text.size), littleEndian = false)
        else -> utf16(text, littleEndian ?: (text.size >= 2 && text[0].toInt() != 0 && text[1].toInt() == 0))
    }
}

private fun utf16(bytes: ByteArray, littleEndian: Boolean): String = buildString {
    for (i in 0 until bytes.size / 2) {
        val lo = bytes[2 * i + if (littleEndian) 0 else 1].toInt() and 0xFF
        val hi = bytes[2 * i + if (littleEndian) 1 else 0].toInt() and 0xFF
        append(((hi shl 8) or lo).toChar())
    }
}

/** GPSDateStamp "YYYY:MM:DD" and GPSTimeStamp's hours, minutes and seconds, in UTC, to the second: Android gives no finer. */
internal fun exifGpsTime(date: String?, hours: Double?, minutes: Double?, seconds: Double?): Instant? {
    if (date == null || hours == null || minutes == null || seconds == null) return null
    val parts = date.trim().split(':').map { it.toIntOrNull() ?: return null }
    if (parts.size != 3 || hours !in 0.0..23.0 || minutes !in 0.0..59.0 || seconds < 0 || seconds >= 61) return null
    return try {
        val day = LocalDate(parts[0], parts[1], parts[2])
        day.atTime(LocalTime(hours.toInt(), minutes.toInt(), seconds.toInt().coerceAtMost(59))).toInstant(TimeZone.UTC)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** GPSTimeStamp as ImageIO gives it: "HH:mm:ss" with optional fractions. */
internal fun exifGpsTime(date: String?, time: String?): Instant? {
    val parts = time?.trim()?.split(':')?.takeIf { it.size == 3 } ?: return null
    return exifGpsTime(date, parts[0].toDoubleOrNull(), parts[1].toDoubleOrNull(), parts[2].toDoubleOrNull())
}

/** GPSSpeed in its GPSSpeedRef's unit: "K" km/h, the default; "M" mph; "N" knots. */
internal fun exifSpeedKmh(raw: Double?, ref: String?): Double? {
    val factor = when (ref?.trim()?.uppercase()) {
        null, "", "K" -> 1.0
        "M" -> 1.609344
        "N" -> 1.852
        else -> return null
    }
    return raw?.times(factor)?.takeIf { it.isFinite() && it >= 0 }
}
