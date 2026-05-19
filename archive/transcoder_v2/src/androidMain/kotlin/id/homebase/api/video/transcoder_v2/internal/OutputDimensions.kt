package id.homebase.api.video.transcoder_v2.internal

/**
 * Short-edge scaling + multiple-of-16 rounding per SPEC §6 / §9.4. The
 * `+ 7` is a slight bias (rounds halfway-cases up). Many encoders require
 * width/height to be multiples of 16 (macroblock alignment for H.264) +
 * iOS playback quirk inherited from Signal.
 */
internal data class OutputDimensions(val width: Int, val height: Int)

/**
 * Scale the source to fit [shortEdgePx] on the shorter dimension while
 * preserving aspect ratio, then round both dimensions up to a multiple
 * of 16. If the source is already smaller than the target on the short
 * edge, no upscaling — return source dimensions rounded.
 */
internal fun computeOutputDimensions(
    sourceWidth: Int,
    sourceHeight: Int,
    shortEdgePx: Int,
): OutputDimensions {
    require(sourceWidth > 0 && sourceHeight > 0) {
        "source dimensions must be positive (got ${sourceWidth}x$sourceHeight)"
    }
    val shortEdge = minOf(sourceWidth, sourceHeight)
    val targetShort = minOf(shortEdge, shortEdgePx)
    val outW: Int
    val outH: Int
    if (sourceWidth < sourceHeight) {
        outW = targetShort
        outH = sourceHeight * outW / sourceWidth
    } else {
        outH = targetShort
        outW = sourceWidth * outH / sourceHeight
    }
    return OutputDimensions(
        width = roundUpToMultipleOf16(outW),
        height = roundUpToMultipleOf16(outH),
    )
}

internal fun roundUpToMultipleOf16(n: Int): Int = (n + 7) and 0xF.inv()
