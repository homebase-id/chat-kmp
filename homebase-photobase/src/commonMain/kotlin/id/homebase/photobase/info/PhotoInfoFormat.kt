package id.homebase.photobase.info

import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.media.MediaLocation
import id.homebase.common.util.formatDecimal
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import id.homebase.api.file.extensionForContentType
import id.homebase.api.image.ImageMetadata
import id.homebase.api.image.MediaQuality

// Null under 0.05, where it would read "0".
internal fun megapixels(width: Int, height: Int): String? =
    (width.toLong() * height / 1_000_000.0).takeIf { it >= 0.05 }?.let { formatDecimal(it, 1) }

/** As cameras show it: a fraction, "1/250", up to a quarter second or for a whole fraction; else seconds, "0.3". */
internal fun shutter(seconds: Double): String {
    val denominator = 1.0 / seconds
    val whole = abs(denominator - denominator.roundToLong()) < 0.05
    return if (seconds < 1.0 && (denominator >= 4 || (whole && denominator.roundToLong() >= 2))) "1/${denominator.roundToLong()}" else formatDecimal(seconds, 1)
}

/**
 * "Canon EOS 5D", not "Canon Canon EOS 5D", and "NIKON D850", not "NIKON CORPORATION NIKON D850": most cameras' model
 * already names the make, or its first word.
 */
internal fun cameraName(make: String?, model: String?): String? = when {
    model == null -> make
    make == null || model.startsWith(make.substringBefore(' '), ignoreCase = true) -> model
    else -> "$make $model"
}

// https, since geo: opens nothing on desktop or web.
internal fun mapsUrl(at: MediaLocation): String {
    val lat = fiveDecimals(at.latitude)
    val lon = fiveDecimals(at.longitude)
    return "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=$MAP_ZOOM/$lat/$lon"
}

internal fun coordinates(at: MediaLocation): String = "${fiveDecimals(at.latitude)}, ${fiveDecimals(at.longitude)}"

/** The box to show around the unit-space point ([x], [y]): one tile across at [MAP_ZOOM], centred on it. */
internal fun mapBox(x: Double, y: Double): DoubleArray {
    val half = 0.5 / (1 shl MAP_ZOOM)
    return doubleArrayOf(x - half, y - half, x + half, y + half)
}

internal const val MAP_ZOOM = 15

/**
 * When it was taken and the offset it was taken at: a time set by hand, at [offsetByHand]; else the EXIF's own time and
 * offset when it has both, since the backup may have read them differently on another platform; else [capturedAtMs]
 * at the EXIF's offset, or with none.
 */
internal fun takenAt(capturedAtMs: Long, exif: ImageMetadata?, timeByHand: Boolean = false, offsetByHand: UtcOffset? = null): Pair<Instant, UtcOffset?> {
    if (timeByHand) return Instant.fromEpochMilliseconds(capturedAtMs) to offsetByHand
    val offset = exif?.captureUtcOffset
    val local = exif?.capturedAt
    return if (local != null && offset != null) local.toInstant(offset) to offset else Instant.fromEpochMilliseconds(capturedAtMs) to offset
}

/** "+0.7", "0", "-1.3": as cameras show it, with its sign. */
internal fun exposureBias(ev: Double): String = formatDecimal(ev, 1).let { if (ev > 0 && it != "0") "+$it" else it }

// [at] shifted so a formatter in the [system] zone shows the clock at [offset]; twice, in case the system's offset
// changes in between. A time the system's clock skips reads an hour off.
internal fun shiftedTo(at: Instant, offset: UtcOffset, system: TimeZone): Instant {
    val first = at + (offset.totalSeconds - system.offsetAt(at).totalSeconds).seconds
    return at + (offset.totalSeconds - system.offsetAt(first).totalSeconds).seconds
}

internal data class FileFacts(
    val name: String?,
    val width: Int?,
    val height: Int?,
    val bytes: Long?,
    /** "JPG", "HEIC", "MP4": what's stored, which at Standard or HD is the re-encoded copy. */
    val type: String? = null,
    val quality: MediaQuality? = null,
    /** The file as it was on the phone. */
    val untouched: Boolean = false,
)

internal fun fileFacts(payload: PayloadDescriptor): FileFacts {
    val bytes = payload.bytesWritten?.takeIf { it > 0 }
    val type = extensionForContentType(payload.contentType).takeIf { it != "bin" }?.uppercase()
    return when (val info = payload.descriptorInfo()) {
        is DescriptorContent.ImageFile -> FileFacts(info.name, info.pixelWidth, info.pixelHeight, bytes, type, info.quality, info.original == true)
        is DescriptorContent.VideoFile -> FileFacts(info.name, info.widthPx.takeIf { it > 0 }, info.heightPx.takeIf { it > 0 }, bytes, type, untouched = info.original == true)
        else -> FileFacts(null, null, null, bytes, type)
    }
}

private fun fiveDecimals(value: Double): String {
    val scaled = (abs(value) * 100_000).roundToLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return "$sign${scaled / 100_000}.${(scaled % 100_000).toString().padStart(5, '0')}"
}

/** [saved] more than five minutes after [taken]: EXIF clocks have no zone, so both are read as UTC to compare. */
internal fun savedLater(taken: LocalDateTime, saved: LocalDateTime): Boolean =
    saved.toInstant(TimeZone.UTC) - taken.toInstant(TimeZone.UTC) > 5.minutes
