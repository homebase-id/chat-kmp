package id.homebase.api.media

import android.media.MediaExtractor
import android.media.MediaFormat
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
 * A camera's own motion photo, which isn't committed: push one first, as the skip message says. Its clip is cut and
 * transcoded as Photos backs it up (THUMBNAILS.md, "Live and motion photos").
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
        val photo = File(context.getExternalFilesDir(null), "motion.MP.jpg")
        assumeTrue("adb push <PXL_….MP.jpg> ${photo.path}", photo.isFile)

        val clip = assertNotNull(MotionPhoto.extractClip(photo.path, fileOps), "the camera's clip is found")
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
    }
}
