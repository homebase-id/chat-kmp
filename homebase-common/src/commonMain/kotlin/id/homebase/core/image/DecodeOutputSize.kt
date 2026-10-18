package id.homebase.core.image

import coil3.annotation.ExperimentalCoilApi
import coil3.decode.DecodeUtils
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size

/** Pixel size a Coil decoder should produce for an upright [srcWidth]x[srcHeight] source. */
@OptIn(ExperimentalCoilApi::class)
fun decodeOutputSize(
    srcWidth: Int,
    srcHeight: Int,
    size: Size,
    scale: Scale,
    maxBitmapSize: Size,
    precision: Precision,
): Pair<Int, Int> {
    val dst = DecodeUtils.computeDstSize(srcWidth, srcHeight, size, scale, maxBitmapSize)
    var multiplier = DecodeUtils.computeSizeMultiplier(srcWidth, srcHeight, dst.first, dst.second, scale, maxBitmapSize)
    if (precision == Precision.INEXACT) multiplier = multiplier.coerceAtMost(1.0)
    return (multiplier * srcWidth).toInt() to (multiplier * srcHeight).toInt()
}
