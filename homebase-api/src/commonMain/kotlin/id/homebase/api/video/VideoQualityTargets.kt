package id.homebase.api.video

import id.homebase.api.image.MediaQuality

/**
 * The bitrate + dimension targets for a given [VideoQuality]. Single source of
 * truth so Android, iOS, and Desktop produce comparable output for the same
 * `quality` enum value, whichever backend encodes (ffmpeg, or Media3 on Android).
 */
data class QualityTargets(
    /** Target short-edge dimension in pixels. Source is scaled DOWN to this; not up. */
    val shortEdgePx: Int,
    /** Target average video bitrate, bits per second. */
    val videoBitrateBps: Int,
    /** Target AAC audio bitrate, bits per second, when audio is re-encoded (Android copies AAC as it is). */
    val audioBitrateBps: Int,
)

fun VideoQuality.targets(): QualityTargets = when (this) {
    VideoQuality.LOW      -> QualityTargets(shortEdgePx = 480,  videoBitrateBps = 1_250_000, audioBitrateBps = 128_000)
    VideoQuality.STANDARD -> QualityTargets(shortEdgePx = 720,  videoBitrateBps = 2_500_000, audioBitrateBps = 128_000)
    VideoQuality.HIGH     -> QualityTargets(shortEdgePx = 1080, videoBitrateBps = 5_000_000, audioBitrateBps = 192_000)
}

/**
 * Returns the (width, height) the encoder should produce, or null if the
 * source short edge is already at-or-below [shortEdgePx] (no scaling needed
 * — encode at source dims).
 *
 * Both output dims rounded UP to the nearest even integer — libx264 (and
 * h264_videotoolbox) require even W/H.
 */
internal fun computeOutputDims(srcW: Int, srcH: Int, shortEdgePx: Int): Pair<Int, Int>? {
    if (srcW <= 0 || srcH <= 0) return null
    val srcShort = minOf(srcW, srcH)
    if (srcShort <= shortEdgePx) return null  // no upscaling; no scale arg needed
    val (outW, outH) = if (srcW < srcH) {
        // Portrait
        shortEdgePx to (srcH * shortEdgePx / srcW)
    } else {
        // Landscape (or square)
        (srcW * shortEdgePx / srcH) to shortEdgePx
    }
    return outW.toEven() to outH.toEven()
}

internal fun Int.toEven(): Int = if (this and 1 == 0) this else this + 1

/**
 * What a hardware encoder (Media3, AVFoundation) is asked for. It spends the whole bitrate it's given, where libx264
 * undershoots on simple content, so a source smaller than the target gets the bitrate in proportion to its area, and
 * no source gets more than its own. A setting a person can pick never gets less than a lower one does for the same
 * source: scaled by area, HD's 1080p rate falls below Standard's for a source between 720p and 1080p.
 */
internal fun QualityTargets.hardwareVideoBitrate(srcW: Int, srcH: Int, srcBitrateBps: Int?): Int {
    val shortSide = minOf(srcW, srcH)
    val lowerSettings = MediaQuality.entries.map { it.toVideoQuality().targets() }.filter { it.shortEdgePx < shortEdgePx }
    val forSize = (lowerSettings.map { it.bitrateFor(shortSide) } + bitrateFor(shortSide)).max()
    return srcBitrateBps?.let { minOf(forSize, it) } ?: forSize
}

private fun QualityTargets.bitrateFor(shortSide: Int): Int =
    if (shortSide in 1 until shortEdgePx) {
        val side = shortSide.toDouble() / shortEdgePx
        (videoBitrateBps * side * side).toInt()
    } else {
        videoBitrateBps
    }
