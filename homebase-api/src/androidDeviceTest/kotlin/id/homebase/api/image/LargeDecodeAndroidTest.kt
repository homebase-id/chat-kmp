package id.homebase.api.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sources over 24 MP, where a decoder that shrinks while decoding would alias or cost memory. Run with
 * the phone plugged in, and read the measurements from `adb logcat -s LargeDecode`:
 *
 *   ./gradlew homebase-api:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=id.homebase.api.image.LargeDecodeAndroidTest
 *
 * `Gradient_48mp.heic` is an 8064x6048 gradient made with macOS `sips`, so only its size matters.
 */
@RunWith(AndroidJUnit4::class)
class LargeDecodeAndroidTest {

    @Test
    fun onePixelStripesOverTwentyFourMegapixelsTurnGrey() = runTest(timeout = 10.minutes) {
        val failures = mutableListOf<String>()
        for (target in listOf(STANDARD_EDGE, HD_EDGE)) {
            val (width, height) = calculateTargetDimensions(STRIPES_WIDTH, STRIPES_HEIGHT, target, target)
            val image = renderImages(stripesPng) { listOf(ImageOutput(width, height, 100, Int.MAX_VALUE, ImageFormat.PNG)) }.images.single()
            val spread = deviation(middleGreens(image.bytes))
            val line = "${STRIPES_WIDTH}x$STRIPES_HEIGHT -> ${width}x$height: spread $spread"
            Log.i(TAG, line)
            if (spread > MAX_SPREAD) failures += line
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun aLargeDecodePeaksNoHigherThanAFullDecode() {
        val cases = listOf(
            Triple("48 MP HEIC", heic48, STANDARD_EDGE),
            Triple("48 MP HEIC", heic48, HD_EDGE),
            Triple("50 MP JPEG", gradientJpeg, HD_EDGE),
            Triple("28 MP PNG", stripesPng, STANDARD_EDGE),
            Triple("28 MP PNG", stripesPng, HD_EDGE),
        )
        val failures = mutableListOf<String>()
        for ((name, bytes, target) in cases) {
            var decoded = ""
            val pipeline = peakNativeBytesDuring {
                openImageSource(bytes).use { source -> source.decode(target).use { decoded = "${it.width}x${it.height}" } }
            }
            val full = peakNativeBytesDuring {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.recycle()
            }
            val line = "$name for $target: decoded $decoded peaking at ${pipeline.mb()}; a full decode peaks at ${full.mb()}"
            Log.i(TAG, line)
            if (pipeline > full * MEASUREMENT_SLACK) failures += line
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // Bitmap pixels live in the native heap; the sampler catches a decoder's temporary bitmap, which never reaches Java.
    private fun peakNativeBytesDuring(block: () -> Unit): Long {
        repeat(2) { System.gc(); System.runFinalization() }
        val baseline = Debug.getNativeHeapAllocatedSize()
        val peak = AtomicLong(baseline)
        val running = AtomicBoolean(true)
        val sampler = thread {
            while (running.get()) {
                peak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
                Thread.sleep(1)
            }
        }
        try {
            block()
        } finally {
            running.set(false)
            sampler.join()
        }
        return peak.get() - baseline
    }

    private fun Long.mb() = "${this / 1_000_000} MB"

    private companion object {
        const val TAG = "LargeDecode"
        const val STRIPES_WIDTH = 7000
        const val STRIPES_HEIGHT = 4000
        const val STANDARD_EDGE = 2048
        const val HD_EDGE = 4096
        const val MAX_SPREAD = 5.0
        const val MEASUREMENT_SLACK = 1.05

        val heic48: ByteArray by lazy {
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("test_images/Gradient_48mp.heic").use { it.readBytes() }
        }

        // Row by row, so the 112 MB of pixels never sit on the Java heap as an IntArray.
        val stripesPng: ByteArray by lazy {
            val bitmap = Bitmap.createBitmap(STRIPES_WIDTH, STRIPES_HEIGHT, Bitmap.Config.ARGB_8888)
            val row = IntArray(STRIPES_WIDTH) { if (it % 2 == 0) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            for (y in 0 until STRIPES_HEIGHT) bitmap.setPixels(row, 0, STRIPES_WIDTH, 0, y, STRIPES_WIDTH, 1)
            bitmap.encodedAs(Bitmap.CompressFormat.PNG)
        }

        val gradientJpeg: ByteArray by lazy {
            val bitmap = Bitmap.createBitmap(8160, 6120, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply {
                shader = LinearGradient(0f, 0f, 8160f, 6120f, android.graphics.Color.BLUE, android.graphics.Color.YELLOW, Shader.TileMode.CLAMP)
            }
            Canvas(bitmap).drawPaint(paint)
            bitmap.encodedAs(Bitmap.CompressFormat.JPEG)
        }

        fun Bitmap.encodedAs(format: Bitmap.CompressFormat): ByteArray =
            ByteArrayOutputStream().also { compress(format, 90, it) }.toByteArray().also { recycle() }
    }
}
