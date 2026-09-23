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

    private class Step(
        val maxSide: Int,
        val colors: Int,
        val fps: Int? = null,
        val fitsUpTo: Double = Double.POSITIVE_INFINITY,
    )

    // fitsUpTo = input/budget past which a step can't fit. Measured output/input on 35 MB and 2.7 MB GIFs: step 1
    // 0.29-0.45, step 2 0.14; each futile step re-encodes the whole input (step 1 took 43 s on a Snapdragon 636).
    private val ladder = listOf(
        Step(512, 128, fitsUpTo = 3.5),
        Step(384, 128, fitsUpTo = 7.0),
        Step(384, 64, fps = 12),
    )

    internal enum class OverBudget { InputTooLarge, TooManyPixels, Timeout, FfmpegFailed, FrameLoss, LadderExhausted }

    /** [overBudget] is null when [bytes] fits, or when the input was never a GIF to shrink. */
    internal class Shrunk(val bytes: ByteArray, val overBudget: OverBudget?)

    internal fun startStep(inputBytes: Int, maxBytes: Long): Int =
        ladder.indexOfFirst { inputBytes <= it.fitsUpTo * maxBytes }

    /** First re-encode under [maxBytes], else the smallest, else [bytes]; non-GIFs and failures return [bytes]. */
    suspend fun shrink(bytes: ByteArray, maxBytes: Long): ByteArray = withContext(Dispatchers.Default) {
        shrink(bytes, maxBytes, VideoCompressionService::transcode).bytes
    }

    internal suspend fun shrink(
        bytes: ByteArray,
        maxBytes: Long,
        transcode: suspend (input: ByteArray, extension: String, outputArgs: List<String>) -> ByteArray?,
    ): Shrunk {
        if (bytes.size <= maxBytes) return Shrunk(bytes, null)
        val gif = ImageFormatDetector.parseGif(bytes)?.takeIf { it.width > 0 && it.height > 0 }
            ?: return Shrunk(bytes, null)

        val started = TimeSource.Monotonic.markNow()
        val start = (startStep(bytes.size, maxBytes) until ladder.size)
            .firstOrNull { ladder[it].bufferedPixels(gif) <= MAX_BUFFERED_PIXELS }
        var best = bytes
        var steps = 0
        var thrown: Exception? = null
        fun done(overBudget: OverBudget?): Shrunk {
            val fps = gif.frameCount * 100 / gif.durationCs
            val summary = "${gif.width}x${gif.height} ${gif.frameCount}f $fps fps ${bytes.size} B -> ${best.size} B, " +
                "start=${start?.plus(1)} steps=$steps ${started.elapsedNow().inWholeMilliseconds} ms"
            if (overBudget == null) Logger.i(tag = TAG) { summary }
            else Logger.w(thrown, TAG) { "Over the $maxBytes B budget, $overBudget: $summary" }
            return Shrunk(best, overBudget)
        }
        if (bytes.size > MAX_INPUT_BYTES) return done(OverBudget.InputTooLarge)
        if (start == null) return done(OverBudget.TooManyPixels)

        val minFrames = min(2, gif.frameCount)
        var stopped = OverBudget.LadderExhausted
        val finished = withTimeoutOrNull(TIMEOUT_MS) {
            for ((i, args) in ladder.map { it.args(gif) }.withIndex().drop(start).distinctBy { it.value }) {
                steps++
                val stepStarted = TimeSource.Monotonic.markNow()
                val out = try {
                    transcode(bytes, "gif", args)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    thrown = e
                    null
                }
                Logger.d(tag = TAG) {
                    "Step ${i + 1} [${args.last()}] -> ${out?.size} B, " +
                        "${stepStarted.elapsedNow().inWholeMilliseconds} ms"
                }
                if (out == null) {
                    stopped = OverBudget.FfmpegFailed
                    break
                }
                if ((ImageFormatDetector.parseGif(out)?.frameCount ?: 0) < minFrames) {
                    stopped = OverBudget.FrameLoss
                    break
                }
                if (out.size < best.size) best = out
                if (best.size <= maxBytes) break
            }
        } != null
        return done(
            when {
                best.size <= maxBytes -> null
                !finished -> OverBudget.Timeout
                else -> stopped
            }
        )
    }

    private fun Step.args(gif: GifInfo): List<String> {
        val (w, h) = gif.fitWithin(maxSide)
        val fpsFilter = loweredFps(gif)?.let { "fps=$it," } ?: ""
        return listOf(
            "-loglevel", "error",
            "-filter_complex",
            "${fpsFilter}scale=$w:$h:flags=lanczos,split[a][b];" +
                "[a]palettegen=max_colors=$colors:stats_mode=diff[p];" +
                "[b][p]paletteuse=dither=none:diff_mode=rectangle",
        )
    }

    // Only ever lower the frame rate: fps= on a slower source would duplicate frames.
    private fun Step.loweredFps(gif: GifInfo): Int? =
        fps?.takeIf { gif.frameCount * 100L > it.toLong() * gif.durationCs }

    private fun Step.bufferedPixels(gif: GifInfo): Long {
        val (w, h) = gif.fitWithin(maxSide)
        val frames = loweredFps(gif)?.let { (gif.durationCs.toLong() * it + 99) / 100 } ?: gif.frameCount.toLong()
        return frames * w * h
    }
}

private fun GifInfo.fitWithin(maxSide: Int) = calculateTargetDimensions(width, height, maxSide, maxSide)
