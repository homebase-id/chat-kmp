package id.homebase.api.media

import id.homebase.api.file.JvmFileOperationsProvider
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue

class MotionPhotoTest {
    private val fileOps = JvmFileOperationsProvider()
    private val workDir = File.createTempFile("motion", "test").let { it.delete(); it.mkdirs(); it }

    @AfterTest
    fun close() {
        workDir.deleteRecursively()
    }

    private fun file(name: String, bytes: ByteArray) = File(workDir, name).apply { writeBytes(bytes) }.path

    @Test
    fun theClipIsCutFromTheEndOfAPixelMotionPhoto() = runTest {
        val clip = fakeMp4(5000)
        val gainmap = ByteArray(777) { 7 }
        val path = file("PXL.MP.jpg", motionPhoto(jpeg(), pixelXmp(gainmap.size.toLong(), clip.size.toLong()), gainmap + clip))

        val out = assertNotNull(MotionPhoto.extractClip(path, fileOps))

        assertContentEquals(clip, File(out).readBytes())
        File(out).delete()
    }

    @Test
    fun aClipLongerThanOneReadIsCopiedWhole() = runTest {
        val clip = fakeMp4(700_000)
        val path = file("PXL.MP.jpg", motionPhoto(jpeg(), pixelXmp(null, clip.size.toLong()), clip))

        val out = File(assertNotNull(MotionPhoto.extractClip(path, fileOps)))
        assertContentEquals(clip, out.readBytes())
        out.delete()
    }

    @Test
    fun aClipThatIsntWhereTheXmpSaysIsNone() = runTest {
        val clip = fakeMp4(5000)
        assertNull(MotionPhoto.clipRange(file("short.jpg", motionPhoto(jpeg(), pixelXmp(null, clip.size + 10L), clip)), fileOps), "the length is off")
        assertNull(MotionPhoto.clipRange(file("huge.jpg", motionPhoto(jpeg(), pixelXmp(null, 99_000_000), clip)), fileOps), "longer than the file")
        assertNull(MotionPhoto.clipRange(file("notmp4.jpg", motionPhoto(jpeg(), pixelXmp(null, 5000), ByteArray(5000))), fileOps), "not an MP4")
    }

    @Test
    fun aPlainJpegHasNoClip() = runTest {
        assertNull(MotionPhoto.extractClip(file("plain.jpg", jpeg()), fileOps))
        assertNull(MotionPhoto.extractClip(file("notanimage", "hello".encodeToByteArray()), fileOps))
    }

    // A camera's own file, which isn't committed: MOTION_PHOTO=/path/to/PXL_….MP.jpg.
    @Test
    fun aRealMotionPhotosClipIsAnMp4ThatRunsToTheEnd() = runTest {
        val real = System.getenv("MOTION_PHOTO")?.let(::File)
        assumeTrue("MOTION_PHOTO isn't set", real?.isFile == true)
        val range = assertNotNull(MotionPhoto.clipRange(real!!.path, fileOps))
        val bytes = real.readBytes()
        assertEquals(bytes.size.toLong() - 1, range.last, "the clip runs to the end of the file")
        assertEquals("ftyp", bytes.decodeToString(range.first.toInt() + 4, range.first.toInt() + 8))
    }
}

/** [jpeg] with [xml] as its XMP, right after its SOI, and [tail] appended, as a camera writes a motion photo. */
internal fun motionPhoto(jpeg: ByteArray, xml: String, tail: ByteArray): ByteArray {
    val payload = "http://ns.adobe.com/xap/1.0/\u0000".encodeToByteArray() + xml.encodeToByteArray()
    val length = payload.size + 2
    val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size) + tail
}

internal fun fakeMp4(size: Int) = ByteArray(size) { (it * 13).toByte() }.also {
    byteArrayOf(0, 0, 0, 24).copyInto(it)
    "ftypisom".encodeToByteArray().copyInto(it, 4)
}

internal fun jpeg(width: Int = 64, height: Int = 48): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, (x * 31 + y * 17) and 0xFFFFFF)
    return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
}
