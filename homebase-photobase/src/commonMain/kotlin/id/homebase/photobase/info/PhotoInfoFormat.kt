package id.homebase.photobase.info

import id.homebase.api.client.location.WebMercator
import id.homebase.api.media.MediaLocation
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt

/** Megapixels to one decimal: 4032 × 3024 is "12.2". */
internal fun megapixels(width: Int, height: Int): String = oneDecimal(width.toLong() * height / 1_000_000.0)

/** An exposure under a second as its fraction, "1/250"; longer ones as seconds, "2.5". */
internal fun shutter(seconds: Double): String =
    if (seconds < 1.0) "1/${(1.0 / seconds).roundToLong()}" else oneDecimal(seconds)

/** An f-number to one decimal, as cameras show it: 1.8, 2, 11. */
internal fun aperture(fNumber: Double): String = oneDecimal(fNumber)

internal fun focalLength(mm: Double): String = oneDecimal(mm)

/** The zone's offset at [at], "GMT", "GMT-8" or "GMT+5:30". */
internal fun zoneLabel(at: Instant, zone: TimeZone): String {
    val seconds = zone.offsetAt(at).totalSeconds
    if (seconds == 0) return "GMT"
    val minutes = abs(seconds) / 60
    val sign = if (seconds < 0) "-" else "+"
    return "GMT$sign${minutes / 60}" + if (minutes % 60 == 0) "" else ":${(minutes % 60).toString().padStart(2, '0')}"
}

/** "Canon EOS 5D", not "Canon Canon EOS 5D": most cameras' model already names the make. */
internal fun cameraName(make: String?, model: String?): String? = when {
    model == null -> make
    make == null || model.startsWith(make, ignoreCase = true) -> model
    else -> "$make $model"
}

/** "37.77490, -122.41940". */
internal fun coordinates(at: MediaLocation): String = "${fiveDecimals(at.latitude)}, ${fiveDecimals(at.longitude)}"

/** Where [at] sits on the map, in Web Mercator unit space, and the box to show around it at [MAP_ZOOM]. */
internal class MapFocus(at: MediaLocation) {
    val x: Double
    val y: Double
    val bbox: DoubleArray

    init {
        val (ux, uy) = WebMercator.latLonToUnit(at.latitude, at.longitude)
        x = ux
        y = uy
        val half = 0.5 / (1 shl MAP_ZOOM)
        bbox = doubleArrayOf(ux - half, uy - half, ux + half, uy + half)
    }
}

internal const val MAP_ZOOM = 15

private fun oneDecimal(value: Double): String {
    val tenths = (value * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${abs(tenths % 10)}"
}

private fun fiveDecimals(value: Double): String {
    val scaled = (abs(value) * 100_000).roundToLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return "$sign${scaled / 100_000}.${(scaled % 100_000).toString().padStart(5, '0')}"
}
