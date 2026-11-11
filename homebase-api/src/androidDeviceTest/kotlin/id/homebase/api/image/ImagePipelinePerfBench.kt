package id.homebase.api.image

import android.graphics.Bitmap
import android.os.Debug
import android.util.Log
import androidx.heifwriter.HeifWriter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.TimeSource

/** Time and peak memory of the image pipeline on a phone; see OPTIMIZE.md for how to run it. */
@RunWith(AndroidJUnit4::class)
class ImagePipelinePerfBench {
    private val args = InstrumentationRegistry.getArguments()
    private val runs = args.getString("imageBenchRuns")?.toInt() ?: 3

    @Test
    fun measure() = runBlocking {
        assumeTrue("Pass imageBench=true to run", args.getString("imageBench") == "true")
        results.delete()
        log("maxMemory=${Runtime.getRuntime().maxMemory() / MB} MB, runs=$runs")
        var jpeg12: ByteArray? = null
        for ((width, height) in listOf(4000 to 3000, 8000 to 6000)) {
            val pixels = photoLike(width, height)
            val jpeg = jpeg(pixels)
            val heic = heic(pixels, width, height)
            pixels.recycle()
            if (jpeg12 == null) jpeg12 = jpeg
            log("${width}x$height JPEG ${jpeg.size / 1024} KB, HEIC ${heic.size / 1024} KB")
            for ((format, bytes) in listOf("JPEG" to jpeg, "HEIC" to heic)) {
                for (quality in listOf(MediaQuality.STANDARD, MediaQuality.HD)) {
                    measure("createMediaImages ${width}x$height $format ${quality.name}") { createMediaImages(bytes, "bench", quality) }
                }
            }
        }
        openImageSource(jpeg12!!).use { source ->
            source.decode(4000).use { full ->
                measure("Lanczos 4000x3000 -> 2048x1536") { full.resampledTo(2048, 1536).close() }
                full.resampledTo(2048, 1536).use { mid ->
                    measure("Lanczos 2048x1536 -> 1080x810") { mid.resampledTo(1080, 810).close() }
                }
            }
        }
        log("done")
    }

    private suspend fun measure(name: String, block: suspend () -> Unit) {
        block()
        val samples = List(runs) { sample(block) }
        val time = samples.map { it.first }.sorted()[runs / 2]
        val native = samples.maxOf { it.second }
        val heap = samples.maxOf { it.third }
        log("$name: median $time ms, peak native +${native / MB} MB, peak Java heap +${heap / MB} MB")
    }

    private suspend fun sample(block: suspend () -> Unit): Triple<Long, Long, Long> {
        val runtime = Runtime.getRuntime()
        System.gc()
        Thread.sleep(300)
        val nativeBase = Debug.getNativeHeapAllocatedSize()
        val heapBase = runtime.totalMemory() - runtime.freeMemory()
        var nativePeak = nativeBase
        var heapPeak = heapBase
        val running = AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                nativePeak = maxOf(nativePeak, Debug.getNativeHeapAllocatedSize())
                heapPeak = maxOf(heapPeak, runtime.totalMemory() - runtime.freeMemory())
                Thread.sleep(1)
            }
        }.apply { start() }
        val start = TimeSource.Monotonic.markNow()
        block()
        val elapsed = start.elapsedNow().inWholeMilliseconds
        running.set(false)
        sampler.join()
        return Triple(elapsed, nativePeak - nativeBase, heapPeak - heapBase)
    }

    // Smooth gradients, hard-edged blocks and sensor-like noise, so the files compress like photos.
    private fun photoLike(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val random = Random(width)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val block = if (((x / 64) + (y / 64)) % 7 == 0) 60 else 0
                val r = 128 + 90 * sin(x / 97.0 + y / 151.0) + random.nextInt(-10, 11) + block
                val g = 128 + 90 * sin(x / 173.0 - y / 89.0) + random.nextInt(-10, 11) - block
                val b = 128 + 90 * sin((x + y) / 211.0) + random.nextInt(-10, 11)
                row[x] = (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        return bitmap
    }

    private fun channel(value: Double) = value.toInt().coerceIn(0, 255)

    private fun jpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()

    private fun heic(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "bench.heic")
        HeifWriter.Builder(file.path, width, height, HeifWriter.INPUT_MODE_BITMAP).setQuality(90).build().use {
            it.start()
            it.addBitmap(bitmap)
            it.stop(60_000)
        }
        return file.readBytes().also { file.delete() }
    }

    // Also to a file, so the results outlive a dropped USB connection: adb pull /sdcard/Android/data/id.homebase.api.test/cache/image-bench.txt
    private val results = File(InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir, "image-bench.txt")

    private fun log(message: String) {
        Log.i(TAG, message)
        results.appendText(message + "\n")
    }

    private companion object {
        const val TAG = "ImageBench"
        const val MB = 1024 * 1024
    }
}
