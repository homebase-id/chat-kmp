package id.homebase.api.image

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.yield

private const val LOBES = 3.0
private const val PRECISION_BITS = 22
private const val ROUNDING = 1 shl (PRECISION_BITS - 1)
private const val CHANNELS = 4
private const val ALPHA_OFFSET = 3

private fun lanczos(x: Double): Double {
    if (x == 0.0) return 1.0
    if (abs(x) >= LOBES) return 0.0
    val px = PI * x
    return LOBES * sin(px) * sin(px / LOBES) / (px * px)
}

/** For each output position, where its window starts in the source and the fixed-point weights across it. */
private class Weights(val starts: IntArray, val counts: IntArray, val perOutput: Int, val values: IntArray)

// The window widens with the reduction, which is what blends every source pixel at any ratio.
private fun weightsFor(sourceSize: Int, outputSize: Int): Weights {
    val ratio = sourceSize.toDouble() / outputSize
    val scale = max(ratio, 1.0)
    val perOutput = ceil(LOBES * scale).toInt() * 2 + 1
    val starts = IntArray(outputSize)
    val counts = IntArray(outputSize)
    val values = IntArray(outputSize * perOutput)
    val raw = DoubleArray(perOutput)
    for (out in 0 until outputSize) {
        val centre = (out + 0.5) * ratio
        val first = max(0, floor(centre - LOBES * scale).toInt())
        val last = min(sourceSize, ceil(centre + LOBES * scale).toInt())
        val count = min(last - first, perOutput)
        var total = 0.0
        for (i in 0 until count) {
            raw[i] = lanczos((first + i + 0.5 - centre) / scale)
            total += raw[i]
        }
        starts[out] = first
        counts[out] = count
        for (i in 0 until count) {
            values[out * perOutput + i] = (raw[i] / total * (1 shl PRECISION_BITS)).toInt()
        }
    }
    return Weights(starts, counts, perOutput, values)
}

private fun clampToByte(value: Int): Byte {
    val shifted = value shr PRECISION_BITS
    return (if (shifted < 0) 0 else if (shifted > 255) 255 else shifted).toByte()
}

// On the web the pipeline shares the one thread with the UI, so a long scale has to let it draw.
private class Pacer {
    private var since = TimeSource.Monotonic.markNow()

    suspend fun pace() {
        if (since.elapsedNow() < SLICE) return
        yield()
        since = TimeSource.Monotonic.markNow()
    }

    private companion object {
        val SLICE = 8.milliseconds
    }
}

private suspend fun horizontal(src: ByteArray, srcWidth: Int, height: Int, outWidth: Int, weights: Weights, pacer: Pacer): ByteArray {
    val out = ByteArray(outWidth * height * CHANNELS)
    for (y in 0 until height) {
        pacer.pace()
        val srcRow = y * srcWidth * CHANNELS
        val outRow = y * outWidth * CHANNELS
        for (x in 0 until outWidth) {
            val start = srcRow + weights.starts[x] * CHANNELS
            val base = x * weights.perOutput
            for (channel in 0 until CHANNELS) {
                var sum = ROUNDING
                for (i in 0 until weights.counts[x]) {
                    sum += (src[start + i * CHANNELS + channel].toInt() and 0xFF) * weights.values[base + i]
                }
                out[outRow + x * CHANNELS + channel] = clampToByte(sum)
            }
        }
    }
    return out
}

private suspend fun vertical(src: ByteArray, width: Int, outHeight: Int, weights: Weights, pacer: Pacer): ByteArray {
    val rowBytes = width * CHANNELS
    val out = ByteArray(rowBytes * outHeight)
    for (y in 0 until outHeight) {
        pacer.pace()
        val start = weights.starts[y] * rowBytes
        val base = y * weights.perOutput
        for (at in 0 until rowBytes) {
            var sum = ROUNDING
            for (i in 0 until weights.counts[y]) {
                sum += (src[start + i * rowBytes + at].toInt() and 0xFF) * weights.values[base + i]
            }
            out[y * rowBytes + at] = clampToByte(sum)
        }
    }
    return out
}

// Ringing can push a premultiplied colour above its alpha, which is not a valid pixel.
private fun clampColoursToAlpha(pixels: ByteArray) {
    for (at in pixels.indices step CHANNELS) {
        val alpha = pixels[at + ALPHA_OFFSET].toInt() and 0xFF
        if (alpha == 255) continue
        for (channel in 0 until ALPHA_OFFSET) {
            if ((pixels[at + channel].toInt() and 0xFF) > alpha) pixels[at + channel] = alpha.toByte()
        }
    }
}

/**
 * Lanczos-3 over premultiplied 8-bit pixels with alpha in the fourth byte; the other three channels
 * can be in any order. Skia's samplers read a fixed 4x4 window, so they alias under 2x.
 */
internal suspend fun resampleLanczos(pixels: ByteArray, width: Int, height: Int, outWidth: Int, outHeight: Int): ByteArray {
    if (outWidth == width && outHeight == height) return pixels
    val pacer = Pacer()
    // Whichever pass shrinks more goes first, so the intermediate is the smaller of the two.
    val result = if (lanczosIntermediateBytes(width, height, outWidth, outHeight) == outWidth.toLong() * height * CHANNELS) {
        val across = if (outWidth == width) pixels else horizontal(pixels, width, height, outWidth, weightsFor(width, outWidth), pacer)
        if (outHeight == height) across else vertical(across, outWidth, outHeight, weightsFor(height, outHeight), pacer)
    } else {
        val down = if (outHeight == height) pixels else vertical(pixels, width, outHeight, weightsFor(height, outHeight), pacer)
        if (outWidth == width) down else horizontal(down, width, outHeight, outWidth, weightsFor(width, outWidth), pacer)
    }
    clampColoursToAlpha(result)
    return result
}

private fun lanczosIntermediateBytes(width: Int, height: Int, outWidth: Int, outHeight: Int): Long =
    min(outWidth.toLong() * height, width.toLong() * outHeight) * CHANNELS
