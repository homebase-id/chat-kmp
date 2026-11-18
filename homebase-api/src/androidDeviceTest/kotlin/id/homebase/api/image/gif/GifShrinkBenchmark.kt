package id.homebase.api.image.gif

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.api.video.GifShrinker
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a check: each GIF in the test app's files/bench re-encoded at every GifShrinker step, timed. Run with
 * `-e gifBench true`; the numbers, and ffmpeg's for the same steps, are in MEDIA_BENCHMARKS.md.
 */
@RunWith(AndroidJUnit4::class)
class GifShrinkBenchmark {

    @Test
    fun eachLadderStep() = runBlocking(Dispatchers.Default) {
        if (InstrumentationRegistry.getArguments().getString("gifBench") != "true") return@runBlocking
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (file in File(context.filesDir, "bench").listFiles { f -> f.extension == "gif" }.orEmpty()) {
            val bytes = file.readBytes()
            val info = ImageFormatDetector.parseGif(bytes) ?: continue
            for (e in GifShrinker.encodings(info)) repeat(2) {
                Thread.sleep(2_000)
                val started = System.nanoTime()
                val out = reencodeGif(bytes, e.width, e.height, e.colors, e.fps)
                val ms = (System.nanoTime() - started) / 1_000_000
                Log.i(
                    "GifBenchmark",
                    "${Build.MODEL} ${file.name} ${info.width}x${info.height} ${info.frameCount}f ${bytes.size / 1000} kB " +
                        "-> $e: $ms ms, ${out?.size?.div(1000)} kB, ${out?.let { ImageFormatDetector.parseGif(it)?.frameCount }} frames",
                )
            }
        }
    }
}
