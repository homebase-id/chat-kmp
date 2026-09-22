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

    internal fun startStep(inputBytes: Int, maxBytes: Long): Int =
        ladder.indexOfFirst { inputBytes <= it.fitsUpTo * maxBytes }

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
        val start = startStep(bytes.size, maxBytes)
        var best = bytes
        var steps = 0
        val finished = withTimeoutOrNull(TIMEOUT_MS) {
            for ((i, args) in ladder.map { it.args(gif) }.withIndex().drop(start).distinctBy { it.value }) {
                steps++
                val stepStarted = TimeSource.Monotonic.markNow()
                val out = try {
                    transcode(bytes, "gif", args)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(e, TAG) { "ffmpeg threw on step ${i + 1}" }
                    null
                }
                Logger.d(tag = TAG) {
                    "Step ${i + 1} [${args.last()}] -> ${out?.size} B, " +
                        "${stepStarted.elapsedNow().inWholeMilliseconds} ms"
                }
                if (out == null || (ImageFormatDetector.parseGif(out)?.frameCount ?: 0) < minFrames) {
                    Logger.w(tag = TAG) { "Step ${i + 1} produced no usable GIF (${out?.size} B)" }
                    break
                }
                if (out.size < best.size) best = out
                if (best.size <= maxBytes) break
            }
        } != null
        Logger.i(tag = TAG) {
            "${gif.width}x${gif.height} ${gif.frameCount}f ${bytes.size} B -> ${best.size} B, " +
                "start=${start + 1} steps=$steps timedOut=${!finished} " +
                "${started.elapsedNow().inWholeMilliseconds} ms"
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
