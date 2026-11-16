package id.homebase.api.file

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a check: how fast a picked video copies out of the media library (a `content://` stream served by
 * another process) with Java's default 8 KB buffer against 1 MB. Run with `-e readBench true` and a video
 * in the test app's `files/bench/`; the numbers go to MEDIA_BENCHMARKS.md.
 */
@RunWith(AndroidJUnit4::class)
class ContentReadBenchmark {

    @Test
    fun copyFromTheMediaLibrary() {
        if (InstrumentationRegistry.getArguments().getString("readBench") != "true") return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "bench").listFiles { f -> f.extension == "mp4" }?.maxByOrNull { it.length() } ?: return
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "read_bench_${System.nanoTime()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            resolver.openOutputStream(uri)!!.use { out -> source.inputStream().use { it.copyTo(out, 1 shl 20) } }
            val target = File(context.cacheDir, "read_bench.mp4")
            for (round in 0..2) {
                val buffers = listOf(8 * 1024, 1 shl 20).let { if (round % 2 == 0) it else it.reversed() }
                for (buffer in buffers) {
                    Thread.sleep(3_000)
                    val started = System.nanoTime()
                    val copied = resolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it, buffer) } }
                    val ms = (System.nanoTime() - started) / 1_000_000
                    Log.i("ReadBenchmark", "${Build.MODEL}: ${copied / 1_000_000} MB from content:// with ${buffer / 1024} KB: ${ms} ms, ${copied / 1000 / ms} MB/s")
                    target.delete()
                }
            }
        } finally {
            resolver.delete(uri, null, null)
        }
    }
}
