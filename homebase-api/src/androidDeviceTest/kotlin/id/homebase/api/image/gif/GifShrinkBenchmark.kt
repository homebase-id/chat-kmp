package id.homebase.api.image.gif

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.homebase.api.ActivityProvider
import id.homebase.api.image.calculateTargetDimensions
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.api.video.FFmpegVideoCompressor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a check: each GIF in the test app's files/bench re-encoded at GifShrinker's three steps, by our encoder and by
 * ffmpeg with the filters GifShrinker used to give it. Run with `-e gifBench true`; numbers go to MEDIA_BENCHMARKS.md.
 */
@RunWith(AndroidJUnit4::class)
class GifShrinkBenchmark {

    @Test
    fun oursAgainstFfmpeg() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("gifBench") != "true") return@runBlocking
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityProvider.initializeApplicationContext(context)
        val gifs = File(context.filesDir, "bench").listFiles { f -> f.extension == "gif" }.orEmpty()
        for (file in gifs) {
            val bytes = file.readBytes()
            val info = ImageFormatDetector.parseGif(bytes) ?: continue
            for ((maxSide, colors, fps) in listOf(Triple(512, 128, null), Triple(384, 128, null), Triple(384, 64, 12))) {
                val (w, h) = calculateTargetDimensions(info.width, info.height, maxSide, maxSide)
                val lowered = fps?.takeIf { info.frameCount * 100L > it.toLong() * info.durationCs }
                for (round in 0..1) {
                    for (who in if (round == 0) listOf("ours", "ffmpeg") else listOf("ffmpeg", "ours")) {
                        Thread.sleep(2_000)
                        val started = System.nanoTime()
                        val out = if (who == "ours") {
                            reencodeGif(bytes, w, h, colors, lowered)
                        } else {
                            FFmpegVideoCompressor.transcode(
                                bytes, "gif",
                                listOf(
                                    "-loglevel", "error", "-filter_complex",
                                    "${lowered?.let { "fps=$it," } ?: ""}scale=$w:$h:flags=lanczos,split[a][b];" +
                                        "[a]palettegen=max_colors=$colors:stats_mode=diff[p];[b][p]paletteuse=dither=none:diff_mode=rectangle",
                                ),
                            )
                        }
                        val ms = (System.nanoTime() - started) / 1_000_000
                        val frames = out?.let { ImageFormatDetector.parseGif(it)?.frameCount }
                        Log.i(
                            "GifBenchmark",
                            "${Build.MODEL} ${file.name} ${info.width}x${info.height} ${info.frameCount}f ${bytes.size / 1000} kB " +
                                "-> ${w}x$h ${colors}c fps=${lowered ?: "-"}: $who ${ms} ms, ${out?.size?.div(1000)} kB, $frames frames",
                        )
                    }
                }
            }
        }
    }
}
