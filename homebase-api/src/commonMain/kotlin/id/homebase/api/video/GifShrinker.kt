package id.homebase.api.video

import co.touchlab.kermit.Logger
import kotlin.math.max
import kotlin.math.min
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "GifShrinker"

/** Re-encodes an oversized GIF with ffmpeg until it fits a byte budget, keeping it animated. */
object GifShrinker {
    internal const val MAX_INPUT_BYTES = 40 * 1024 * 1024

    // The palette pass buffers every scaled RGBA frame before it writes one, so this bounds memory.
    internal const val MAX_BUFFERED_PIXELS = 32L * 1024 * 1024
    internal const val TIMEOUT_MS = 30_000L

    private class Step(val maxSide: Int, val colors: Int, val fps: Int? = null)

    private val ladder = listOf(Step(512, 128), Step(384, 128), Step(384, 64, fps = 12))

    /**
     * Returns [bytes] untouched unless they are a GIF larger than [maxBytes]. Otherwise returns the
     * first re-encode that fits, else the smallest one, else the original; any failure keeps the original.
     */
    suspend fun shrink(bytes: ByteArray, maxBytes: Long): ByteArray = withContext(Dispatchers.Default) {
        shrink(bytes, maxBytes, VideoCompressionService::transcode)
    }

    internal suspend fun shrink(
        bytes: ByteArray,
        maxBytes: Long,
        transcode: suspend (input: ByteArray, extension: String, outputArgs: List<String>) -> ByteArray?,
        timeoutMs: Long = TIMEOUT_MS,
    ): ByteArray {
        if (bytes.size <= maxBytes || bytes.size > MAX_INPUT_BYTES) return bytes
        val gif = parseGif(bytes) ?: return bytes
        val (bufferedW, bufferedH) = fitWithin(gif.width, gif.height, ladder.first().maxSide)
        if (gif.frameCount.toLong() * bufferedW * bufferedH > MAX_BUFFERED_PIXELS) return bytes

        val started = TimeSource.Monotonic.markNow()
        val minFrames = min(2, gif.frameCount)
        var best = bytes
        var steps = 0
        val finished = withTimeoutOrNull(timeoutMs) {
            for (args in ladder.map { it.args(gif) }.distinct()) {
                steps++
                val out = try {
                    transcode(bytes, "gif", args)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(e, TAG) { "ffmpeg threw on step $steps" }
                    null
                }
                if (out == null || (parseGif(out)?.frameCount ?: 0) < minFrames) {
                    Logger.w(tag = TAG) { "Step $steps produced no usable GIF (${out?.size} B)" }
                    break
                }
                if (out.size < best.size) best = out
                if (best.size <= maxBytes) break
            }
        } != null
        Logger.i(tag = TAG) {
            "${gif.width}x${gif.height} ${gif.frameCount}f ${bytes.size} B -> ${best.size} B, " +
                "steps=$steps timedOut=${!finished} ${started.elapsedNow().inWholeMilliseconds} ms"
        }
        return best
    }

    private fun Step.args(gif: GifInfo): List<String> {
        val (w, h) = fitWithin(gif.width, gif.height, maxSide)
        // Only ever lower the frame rate: fps= on a slower source would duplicate frames.
        val fpsFilter = fps?.takeIf { gif.frameCount * 100 > it * gif.durationCs }?.let { "fps=$it," } ?: ""
        return listOf(
            "-loglevel", "error",
            "-filter_complex",
            "${fpsFilter}scale=$w:$h:flags=lanczos,split[a][b];" +
                "[a]palettegen=max_colors=$colors:stats_mode=diff[p];" +
                "[b][p]paletteuse=dither=none:diff_mode=rectangle",
        )
    }
}

private class GifInfo(val width: Int, val height: Int, val frameCount: Int, val durationCs: Int)

private fun fitWithin(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
    val longest = max(width, height)
    if (longest <= maxSide) return width to height
    return max(1, (width * maxSide + longest / 2) / longest) to max(1, (height * maxSide + longest / 2) / longest)
}

private fun parseGif(b: ByteArray): GifInfo? {
    if (b.size < 13 || b.decodeToString(0, 3) != "GIF") return null
    var i = 13 + gifColorTableSize(b[10])
    var frames = 0
    var durationCs = 0
    var delayCs = 0
    while (i < b.size) {
        when (b[i].toInt() and 0xFF) {
            0x21 -> {
                if (i + 5 < b.size && (b[i + 1].toInt() and 0xFF) == 0xF9) delayCs = u16le(b, i + 4)
                i = skipGifSubBlocks(b, i + 2)
            }
            0x2C -> {
                if (i + 10 > b.size) break
                frames++
                // ffmpeg's GIF demuxer plays a delay under 2 cs at 10 cs.
                durationCs += if (delayCs < 2) 10 else delayCs
                delayCs = 0
                i = skipGifSubBlocks(b, i + 10 + gifColorTableSize(b[i + 9]) + 1)
            }
            else -> break
        }
    }
    val width = u16le(b, 6)
    val height = u16le(b, 8)
    return if (frames > 0 && width > 0 && height > 0) GifInfo(width, height, frames, durationCs) else null
}

private fun u16le(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

private fun gifColorTableSize(packed: Byte): Int =
    if ((packed.toInt() and 0x80) != 0) 3 * (1 shl ((packed.toInt() and 0x07) + 1)) else 0

private fun skipGifSubBlocks(b: ByteArray, start: Int): Int {
    var i = start
    while (i < b.size) {
        val length = b[i].toInt() and 0xFF
        i += 1 + length
        if (length == 0) break
    }
    return i
}
