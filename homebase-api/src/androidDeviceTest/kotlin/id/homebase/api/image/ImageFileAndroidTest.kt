package id.homebase.api.image

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.homebase.api.file.AndroidFileOperationsProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageFileAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fileOps = AndroidFileOperationsProvider(context)
    private val png = ImageUtils.encodeArgbToPng(ArgbImage(IntArray(300 * 200) { 0xFF336699.toInt() }, 300, 200))

    // Orientation 6, so the displayed size is the stored one turned on its side.
    private suspend fun rotatedJpeg(): ByteArray {
        val pixels = IntArray(1600 * 1200) { i -> (0xFF shl 24) or ((i % 1600) % 256 shl 16) or ((i / 1600) % 256 shl 8) or 90 }
        val jpeg = ImageUtils.resizePreserveAspect(ImageUtils.encodeArgbToPng(ArgbImage(pixels, 1600, 1200)), 1600, 1200, ImageFormat.JPEG, 90).bytes
        val tiff = byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0, 0, 0)
        val body = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()) + body
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    @Test
    fun aFileOpensWithoutBeingReadWhole() {
        val file = File(context.cacheDir, "image_file_test.png").apply { writeBytes(png) }
        try {
            assertNotNull(openImageFile(file.absolutePath, fileOps)).use { assertEquals(ImageSize(300, 200), it.displayedSize) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun aContentUriMakesWhatItsBytesMake() = runTest {
        val bytes = rotatedJpeg()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "image_file_test_${System.nanoTime()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val uri = assertNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            assertNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            val path = uri.toString()
            assertNotNull(openImageFile(path, fileOps), "a content URI should open directly").use { assertEquals(ImageSize(1200, 1600), it.displayedSize) }

            val fromBytes = createMediaImages(bytes, "k", MediaQuality.STANDARD, readMetadata = true)
            val fromUri = createMediaImages(path, fileOps, "k", MediaQuality.STANDARD, readMetadata = true)

            assertEquals(fromBytes.displayedSize, fromUri.displayedSize)
            fromBytes.thumbnails.zip(fromUri.thumbnails).forEach { (a, b) -> assertContentEquals(a.thumbnailBytes, b.thumbnailBytes) }
            assertContentEquals(assertNotNull(fromBytes.finalImage).bytes, assertNotNull(fromUri.finalImage).bytes)
            assertEquals(fromBytes.metadata, fromUri.metadata)
            assertEquals(6, readImageMetadata(path, fileOps)?.orientation)
            assertEquals(bytes.size.toLong(), fromUri.sourceByteCount)
        } finally {
            resolver.delete(uri, null, null)
        }
    }
}
