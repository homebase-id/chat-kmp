package id.homebase.api.image

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.lib.image.ImageFormatDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset

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
)

/**
 * Read EXIF / image-file metadata from encoded image bytes (JPEG, HEIC, TIFF,
 * PNG, WebP — depending on platform support).
 *
 * Returns null only when the bytes cannot be parsed at all. If the image has
 * no metadata, an [ImageMetadata] with all-null fields is returned.
 */
expect fun readImageMetadata(srcBytes: ByteArray): ImageMetadata?

/** [readImageMetadata] for the file at [path], reading only its header where the platform can. */
suspend fun readImageMetadata(path: String, fileOps: FileOperationsProvider): ImageMetadata? =
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
        if (ImageFormatDetector.detectFormat(header) == "image/jpeg") {
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
