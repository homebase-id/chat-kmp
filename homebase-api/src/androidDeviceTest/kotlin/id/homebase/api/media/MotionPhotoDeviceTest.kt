package id.homebase.api.media

import android.content.ContentValues
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.homebase.api.file.AndroidFileOperationsProvider
import id.homebase.api.video.Media3VideoCompressor
import id.homebase.api.video.VideoQuality
import id.homebase.api.ActivityProvider
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone camera's newest motion photo, which isn't committed, read through the library as Photos reads it: its clip
 * is cut and transcoded as Photos backs it up (THUMBNAILS.md, "Live and motion photos").
 */
@RunWith(AndroidJUnit4::class)
class MotionPhotoDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fileOps = AndroidFileOperationsProvider(context)

    @Before
    fun setUp() {
        ActivityProvider.initializeApplicationContext(context)
    }

    @Test
    fun aCameraMotionPhotosClipBecomesSdrH264AtStandardAndHd() = runBlocking {
        val name = shell("ls $CAMERA").lines().map { it.trim() }.lastOrNull { it.endsWith(".MP.jpg") }
        assumeTrue("no motion photo in $CAMERA", name != null)
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "motion_${System.nanoTime()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            },
        )!!
        val clip = try {
            resolver.openOutputStream(uri)!!.use { out -> shellStream("cat $CAMERA/$name").use { it.copyTo(out) } }
            assertNotNull(MotionPhoto.extractClip(uri.toString(), fileOps), "the camera's clip is found")
        } finally {
            resolver.delete(uri, null, null)
        }
        try {
            val source = probe(clip)
            Log.i(TAG, "clip ${source.mime} ${source.width}x${source.height} transfer=${source.colorTransfer}")
            for ((quality, shortEdge) in listOf(VideoQuality.STANDARD to 720, VideoQuality.HIGH to 1080)) {
                val out = Media3VideoCompressor.compress(clip, null, null, null, quality, keepMetadata = true)
                try {
                    val sent = probe(out)
                    Log.i(TAG, "$quality ${sent.mime} ${sent.width}x${sent.height} transfer=${sent.colorTransfer}")
                    assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, sent.mime, "$quality: H.264")
                    assertEquals(minOf(shortEdge, minOf(source.width, source.height)), minOf(sent.width, sent.height), "$quality: its short edge")
                    assertTrue(sent.colorTransfer != MediaFormat.COLOR_TRANSFER_ST2084 && sent.colorTransfer != MediaFormat.COLOR_TRANSFER_HLG, "$quality: SDR, transfer ${sent.colorTransfer}")
                } finally {
                    File(out).delete()
                }
            }
        } finally {
            File(clip).delete()
        }
    }

    private fun shellStream(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))

    private fun shell(command: String) = shellStream(command).use { it.readBytes().decodeToString() }

    private class Track(val mime: String?, val width: Int, val height: Int, val colorTransfer: Int?)

    private fun probe(path: String): Track {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            val video = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            return Track(
                video.getString(MediaFormat.KEY_MIME),
                video.getInteger(MediaFormat.KEY_WIDTH),
                video.getInteger(MediaFormat.KEY_HEIGHT),
                if (video.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) video.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else null,
            )
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val TAG = "MotionPhotoDeviceTest"
        const val CAMERA = "/sdcard/DCIM/Camera"
    }
}
