package id.homebase.photobase.info

import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.media.MediaLocation
import id.homebase.common.util.formatDecimal
import kotlin.math.abs
import kotlin.math.roundToLong

/** Megapixels to one decimal: 4032 × 3024 is "12.2". */
internal fun megapixels(width: Int, height: Int): String = formatDecimal(width.toLong() * height / 1_000_000.0, 1)

/** An exposure that's a whole fraction of a second as one, "1/250"; any other as seconds, "0.8" or "2.5". */
internal fun shutter(seconds: Double): String {
    val denominator = 1.0 / seconds
    return if (seconds < 1.0 && abs(denominator - denominator.roundToLong()) < 0.05) "1/${denominator.roundToLong()}" else formatDecimal(seconds, 1)
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

/** A map app's search for the point, as an https link every platform opens: in the maps app where there is one. */
internal fun mapsUrl(at: MediaLocation): String = "https://www.google.com/maps/search/?api=1&query=${at.latitude},${at.longitude}"

/** "37.77490, -122.41940". */
internal fun coordinates(at: MediaLocation): String = "${fiveDecimals(at.latitude)}, ${fiveDecimals(at.longitude)}"

/** The box to show around the unit-space point ([x], [y]): one tile across at [MAP_ZOOM], centred on it. */
internal fun mapBox(x: Double, y: Double): DoubleArray {
    val half = 0.5 / (1 shl MAP_ZOOM)
    return doubleArrayOf(x - half, y - half, x + half, y + half)
}

internal const val MAP_ZOOM = 15

/** What the descriptor says of the file itself. */
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
