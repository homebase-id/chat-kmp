package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.image.calculateTargetDimensions
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.api.lib.image.ImageFormatDetector.GifInfo
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
    private const val MAX_BUFFERED_PIXELS = 32L * 1024 * 1024
    private const val TIMEOUT_MS = 30_000L

    private class Step(val maxSide: Int, val colors: Int, val fps: Int? = null)

    private val ladder = listOf(Step(512, 128), Step(384, 128), Step(384, 64, fps = 12))

    /** First re-encode under [maxBytes], else the smallest, else [bytes]; non-GIFs and failures return [bytes]. */
    suspend fun shrink(bytes: ByteArray, maxBytes: Long): ByteArray = withContext(Dispatchers.Default) {
        shrink(bytes, maxBytes, VideoCompressionService::transcode)
    }

    internal suspend fun shrink(
        bytes: ByteArray,
        maxBytes: Long,
        transcode: suspend (input: ByteArray, extension: String, outputArgs: List<String>) -> ByteArray?,
    ): ByteArray {
        if (bytes.size <= maxBytes || bytes.size > MAX_INPUT_BYTES) return bytes
        val gif = ImageFormatDetector.parseGif(bytes)?.takeIf { it.width > 0 && it.height > 0 } ?: return bytes
        val (bufferedW, bufferedH) = gif.fitWithin(ladder.first().maxSide)
        if (gif.frameCount.toLong() * bufferedW * bufferedH > MAX_BUFFERED_PIXELS) return bytes

        val started = TimeSource.Monotonic.markNow()
        val minFrames = min(2, gif.frameCount)
        var best = bytes
        var steps = 0
        val finished = withTimeoutOrNull(TIMEOUT_MS) {
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
                if (out == null || (ImageFormatDetector.parseGif(out)?.frameCount ?: 0) < minFrames) {
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
        val (w, h) = gif.fitWithin(maxSide)
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

private fun GifInfo.fitWithin(maxSide: Int) = calculateTargetDimensions(width, height, maxSide, maxSide)
