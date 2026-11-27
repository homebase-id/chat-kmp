package id.homebase.photobase.info

import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.media.MediaLocation
import id.homebase.common.util.formatDecimal
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.offsetAt

// Null under 0.05, where it would read "0".
internal fun megapixels(width: Int, height: Int): String? =
    (width.toLong() * height / 1_000_000.0).takeIf { it >= 0.05 }?.let { formatDecimal(it, 1) }

/** As cameras show it: a fraction, "1/250", up to a third of a second or for a whole fraction; else seconds, "0.8". */
internal fun shutter(seconds: Double): String {
    val denominator = 1.0 / seconds
    val whole = abs(denominator - denominator.roundToLong()) < 0.05
    return if (seconds < 1.0 && (denominator >= 3 || whole)) "1/${denominator.roundToLong()}" else formatDecimal(seconds, 1)
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

// An https link every platform opens, in the maps app where there is one; geo: opens nothing on desktop or web.
internal fun mapsUrl(at: MediaLocation): String = "https://www.google.com/maps/search/?api=1&query=${at.latitude},${at.longitude}"

internal fun coordinates(at: MediaLocation): String = "${fiveDecimals(at.latitude)}, ${fiveDecimals(at.longitude)}"

/** The box to show around the unit-space point ([x], [y]): one tile across at [MAP_ZOOM], centred on it. */
internal fun mapBox(x: Double, y: Double): DoubleArray {
    val half = 0.5 / (1 shl MAP_ZOOM)
    return doubleArrayOf(x - half, y - half, x + half, y + half)
}

internal const val MAP_ZOOM = 15

/**
 * [at] shifted so a formatter that shows the [system] zone reads the clock at [offset], the zone the photo was taken
 * in. Near the system zone's own change of offset it can be an hour out.
 */
internal fun shiftedTo(at: Instant, offset: UtcOffset, system: TimeZone): Instant =
    at + (offset.totalSeconds - system.offsetAt(at).totalSeconds).seconds

internal data class FileFacts(val name: String?, val width: Int?, val height: Int?, val bytes: Long?)

internal fun fileFacts(payload: PayloadDescriptor): FileFacts {
    val bytes = payload.bytesWritten?.takeIf { it > 0 }
    return when (val info = payload.descriptorInfo()) {
        is DescriptorContent.ImageFile -> FileFacts(info.name, info.pixelWidth, info.pixelHeight, bytes)
        is DescriptorContent.VideoFile -> FileFacts(info.name, info.widthPx.takeIf { it > 0 }, info.heightPx.takeIf { it > 0 }, bytes)
        else -> FileFacts(null, null, null, bytes)
    }
}

private fun fiveDecimals(value: Double): String {
    val scaled = (abs(value) * 100_000).roundToLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return "$sign${scaled / 100_000}.${(scaled % 100_000).toString().padStart(5, '0')}"
}
