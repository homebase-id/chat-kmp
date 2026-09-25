package id.homebase.core.camera

import kotlin.math.abs
import kotlin.math.roundToInt

data class ZoomPreset(val ratio: Float, val label: String)

// Tolerance and reachability rule derived from Signal-Android hud/ZoomBarLevel.kt (AGPL-3.0, see NOTICE).
object ZoomPresets {
    private const val MATCH_TOLERANCE_FRACTION = 0.02f
    private const val TELEPHOTO_MIN_RATIO = 2.5f

    /**
     * Ultra-wide (when the lens reaches below 1×), 1×, 2×, and each optical telephoto switch point.
     * Digital-only zoom past 2× is left to pinch: a 5× preset on a phone without a tele lens is just a crop.
     */
    fun available(minZoom: Float, maxZoom: Float, lensSwitchRatios: List<Float>): List<ZoomPreset> {
        val range = minZoom..maxOf(minZoom, maxZoom)
        val candidates = buildList {
            if (minZoom < 1f && !isAt(minZoom, 1f)) add(minZoom)
            add(1f)
            add(2f)
            lensSwitchRatios.filter { it >= TELEPHOTO_MIN_RATIO }.forEach { add(it) }
        }
        return candidates
            .filter { isAt(it.coerceIn(range), it) }
            .sorted()
            .fold(mutableListOf<Float>()) { acc, ratio ->
                if (acc.none { isAt(ratio, it) }) acc += ratio
                acc
            }
            .map { ZoomPreset(it, label(it)) }
    }

    fun selected(zoomRatio: Float, presets: List<ZoomPreset>): ZoomPreset? =
        presets.firstOrNull { isAt(zoomRatio, it.ratio) }

    fun label(ratio: Float): String {
        val tenths = (ratio * 10f).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
    }

    private fun isAt(ratio: Float, level: Float): Boolean =
        abs(ratio - level) <= level * MATCH_TOLERANCE_FRACTION
}
