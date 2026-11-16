package id.homebase.api.image

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** THUMBNAILS.md, "HDR": what the full-screen viewer draws from an Ultra HDR photo still carries its gain map. */

@RunWith(AndroidJUnit4::class)
class HdrDisplayDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun coilsScreenSizedDecodeKeepsTheGainmap() = runBlocking {
        assumeTrue("gain maps need Android 14", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val result = ImageLoader(context).execute(ImageRequest.Builder(context).data(ultraHdrJpeg(4000, 3000)).size(1080, 2400).build())
        val bitmap = ((result as SuccessResult).image as BitmapImage).bitmap
        Log.i("HdrDisplay", "coil: ${bitmap.width}x${bitmap.height} ${bitmap.config} gainmap=${bitmap.hasGainmap()}")
        assertTrue(bitmap.hasGainmap(), "Coil's bitmap has the gain map")
    }

    /**
     * Not a check: with `-e hdrBench true`, each photo in the test app's files/bench (or, with `-e hdrSynthetic true`,
     * a generated 12 MP Ultra HDR photo) made into the HD final image four ways, in rotating order: the pipeline's SDR
     * WebP, its SDR JPEG and its Ultra HDR JPEG through the shared writer, and Android's own encoder on the same scaled
     * pixels with and without the gain map. The writer's cost is HDR minus SDR JPEG; Android's is its own pair.
     */
    @Test
    fun benchmarkHdrFinalImages() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("hdrBench") != "true") return@runBlocking
        assumeTrue("gain maps need Android 14", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val photos = File(context.filesDir, "bench").listFiles { f -> f.extension.lowercase() == "jpg" }.orEmpty().sortedBy { it.name }
            .map { it.name to it.readBytes() } + if (args.getString("hdrSynthetic") == "true") listOf("synthetic" to syntheticUltraHdr()) else emptyList()
        for ((name, bytes) in photos) {
            val final = planMediaImages(openImageSource(bytes).use { it.displayedSize }, MediaQuality.HD).finalImage!!
            fun output(format: ImageFormat, hdr: Boolean) = ImageOutput(final.width, final.height, final.quality, Int.MAX_VALUE, format = format, keepsHdr = hdr)
            val variants: List<Pair<String, suspend () -> ByteArray>> = listOf(
                "pipeline SDR WebP" to { openImageSource(bytes).use { renderImages(it) { listOf(output(ImageFormat.WEBP, false)) } }.images.single().bytes },
                "pipeline SDR JPEG" to { openImageSource(bytes).use { renderImages(it) { listOf(output(ImageFormat.JPEG, false)) } }.images.single().bytes },
                "shared Ultra HDR" to {
                    val gainmap = checkNotNull(HdrGainmap.of(bytes) { r -> bytes.copyOfRange(r.first.toInt(), r.last.toInt() + 1) })
                    gainmap.use { g -> openImageSource(bytes).use { renderImages(it, g) { listOf(output(ImageFormat.JPEG, true)) } } }.images.single().bytes
                },
            )
            repeat(3) { round ->
                for (k in variants.indices) {
                    val (label, run) = variants[(k + round) % variants.size]
                    val started = System.nanoTime()
                    val out = run()
                    log(name, bytes, label, out, (System.nanoTime() - started) / 1_000_000)
                }
                androidEncoder(name, bytes, final.width, final.height, final.quality)
            }
        }
    }

    // #2455's path: Android's ImageDecoder keeps the gain map, and compress writes it.
    private fun androidEncoder(name: String, bytes: ByteArray, width: Int, height: Int, quality: Int) {
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
        val scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
        val source = checkNotNull(decoded.gainmap)
        val scaleStarted = System.nanoTime()
        val contents = source.gainmapContents
        val mapWidth = contents.width * width / decoded.width
        val mapHeight = contents.height * height / decoded.height
        val map = contents.colorSpace?.let { Bitmap.createBitmap(mapWidth, mapHeight, contents.config!!, contents.hasAlpha(), it) }
            ?: Bitmap.createBitmap(mapWidth, mapHeight, contents.config!!)
        Canvas(map).drawBitmap(contents, null, Rect(0, 0, mapWidth, mapHeight), Paint(Paint.FILTER_BITMAP_FLAG))
        val scaleMs = (System.nanoTime() - scaleStarted) / 1_000_000
        fun compress(): Pair<ByteArray, Long> {
            val started = System.nanoTime()
            val out = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            return out to (System.nanoTime() - started) / 1_000_000
        }
        val (sdr, sdrMs) = compress()
        scaled.gainmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) Gainmap(source, map) else Gainmap(map).apply {
            source.ratioMin.let { setRatioMin(it[0], it[1], it[2]) }
            source.ratioMax.let { setRatioMax(it[0], it[1], it[2]) }
            source.gamma.let { setGamma(it[0], it[1], it[2]) }
            source.epsilonSdr.let { setEpsilonSdr(it[0], it[1], it[2]) }
            source.epsilonHdr.let { setEpsilonHdr(it[0], it[1], it[2]) }
            minDisplayRatioForHdrTransition = source.minDisplayRatioForHdrTransition
            displayRatioForFullHdr = source.displayRatioForFullHdr
        }
        val (hdr, hdrMs) = compress()
        log(name, bytes, "Android compress SDR JPEG", sdr, sdrMs)
        log(name, bytes, "Android compress Ultra HDR (+$scaleMs ms gain-map scale)", hdr, hdrMs)
        listOf(decoded, scaled, map).forEach { it.recycle() }
    }

    private fun log(name: String, source: ByteArray, label: String, out: ByteArray, ms: Long) =
        Log.i("HdrBench", "${Build.MODEL} $name ${source.size / 1000} kB -> HD $label: ${out.size / 1000} kB, $ms ms")

    // 4032x3024 like a phone photo: a gradient with fine noise, so it compresses like detail, and a smooth gain map.
    private fun syntheticUltraHdr(): ByteArray {
        val width = 4032
        val height = 3024
        val random = java.util.Random(7)
        val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val n = random.nextInt(24) - 12
                val r = (x * 255 / width + n).coerceIn(0, 255)
                val g = (y * 255 / height + n).coerceIn(0, 255)
                val b = ((x + y) * 255 / (width + height) + n).coerceIn(0, 255)
                row[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            base.setPixels(row, 0, width, 0, y, width, 1)
        }
        val map = Bitmap.createBitmap(width / 4, height / 4, Bitmap.Config.ALPHA_8)
        Canvas(map).drawPaint(Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, width / 4f, 0f, 0x00000000, 0xFF000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
        })
        base.gainmap = Gainmap(map).apply { setRatioMax(4f, 4f, 4f); displayRatioForFullHdr = 4f }
        return ByteArrayOutputStream().also { base.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    }

    /** Not a check: with `-e hdrFixture true`, puts a generated Ultra HDR photo in the gallery to open in the app. */
    @Test
    fun putAnUltraHdrPhotoInTheGallery() {
        if (InstrumentationRegistry.getArguments().getString("hdrFixture") != "true") return
        assumeTrue("gain maps need Android 14", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val width = InstrumentationRegistry.getArguments().getString("hdrWidth")?.toInt() ?: 3000
        val height = InstrumentationRegistry.getArguments().getString("hdrHeight")?.toInt() ?: 2000
        val base = Bitmap.createBitmap(IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            (0xFF shl 24) or ((x * 255 / width) shl 16) or ((y * 255 / height) shl 8) or 0x60
        }, width, height, Bitmap.Config.ARGB_8888)
        val map = Bitmap.createBitmap(IntArray(width / 4 * (height / 4)) { i ->
            val bright = if ((i % (width / 4)) > width / 8) 0xFF else 0x20
            (0xFF shl 24) or (bright shl 16) or (bright shl 8) or bright
        }, width / 4, height / 4, Bitmap.Config.ARGB_8888)
        base.gainmap = Gainmap(map).apply { setRatioMax(4f, 4f, 4f); setDisplayRatioForFullHdr(4f) }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "hdr_test_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HomebaseTest")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { base.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        Log.i("HdrDisplay", "gallery: $uri")
    }

    @Test
    fun aZoomTileKeepsTheGainmap() {
        assumeTrue("gain maps need Android 14", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val bytes = ultraHdrJpeg(4000, 3000)
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        val tile = decoder.decodeRegion(Rect(1000, 1000, 2024, 2024), BitmapFactory.Options().apply { inSampleSize = 2 })
        Log.i("HdrDisplay", "tile: ${tile.width}x${tile.height} ${tile.config} gainmap=${tile.hasGainmap()}")
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        Log.i("HdrDisplay", "display: hdr types ${display?.hdrCapabilities?.supportedHdrTypes?.toList()} hdr/sdr ratio available ${display?.isHdrSdrRatioAvailable} ratio ${display?.hdrSdrRatio}")
        assertTrue(tile.hasGainmap(), "the tile has the gain map")
    }
}
