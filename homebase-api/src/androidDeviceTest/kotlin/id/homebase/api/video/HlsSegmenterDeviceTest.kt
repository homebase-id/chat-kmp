package id.homebase.api.video

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.homebase.api.ActivityProvider
import id.homebase.api.client.KeyHeader
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesCbc
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Media3's output cut by our segmenter, then read back by Android's own MPEG-TS extractor. */
@RunWith(AndroidJUnit4::class)
class HlsSegmenterDeviceTest {
    private val keyHeader = KeyHeader(Random.nextBytes(16), SecureByteArray(Random.nextBytes(16)))

    @Before
    fun setUp() {
        ActivityProvider.initializeApplicationContext(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun aLandscapeVideoSegmentsAndPlaysBack() = runBlocking { check("landscape_1080p_gps.mp4", 1280 to 720) }

    @Test
    fun aPortraitVideoSegmentsAsPortraitFrames() = runBlocking { check("portrait_1080p.mp4", 720 to 1280) }

    private suspend fun check(name: String, size: Pair<Int, Int>) {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val source = File(ctx.cacheDir, name).apply { ctx.assets.open("test_videos/$name").use { i -> outputStream().use { i.copyTo(it) } } }
        val compressed = AndroidVideoCompressor.compress(source.absolutePath, null, null, null, VideoQuality.STANDARD, false)
        // Android's TS extractor leaves out the last frame of a stream; ffmpeg's own HLS reads back the same.
        val frames = FFmpegVideoCompressor.segmentAndEncrypt(compressed, keyHeader, null)!!.let { ffmpeg ->
            videoTrack(decryptedTs(ffmpeg, "$name.ffmpeg.ts").absolutePath).second.also { File(ffmpeg.segmentsPath).parentFile!!.deleteRecursively() }
        }

        val started = System.currentTimeMillis()
        val segmented = assertNotNull(AndroidVideoCompressor.segmentAndEncrypt(compressed, keyHeader, null))
        Log.i("HlsSegmenterDeviceTest", "$name: segmented in ${System.currentTimeMillis() - started}ms")
        assertNotNull(segmented.hash, "our segmenter, not ffmpeg's fallback, cut it")

        val ts = decryptedTs(segmented, "$name.ts")

        try {
            val (format, read) = videoTrack(ts.absolutePath)
            assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME))
            assertEquals(size, format.getInteger(MediaFormat.KEY_WIDTH) to format.getInteger(MediaFormat.KEY_HEIGHT))
            assertEquals(frames, read, "every frame ffmpeg's HLS keeps")
        } finally {
            listOf(source, File(compressed), ts, File(segmented.segmentsPath).parentFile!!).forEach { it.deleteRecursively() }
        }
    }

    private suspend fun decryptedTs(segmented: SegmentedVideo, name: String): File {
        val playlist = File(segmented.playlistPath).readText()
        val stored = File(segmented.segmentsPath).readBytes()
        val plain = Regex("#EXT-X-BYTERANGE:(\\d+)@(\\d+)").findAll(playlist).toList().map { m ->
            val (length, offset) = m.destructured
            AesCbc.decrypt(stored.copyOfRange(offset.toInt(), offset.toInt() + length.toInt()), keyHeader.aesKey, keyHeader.iv)
        }.reduce(ByteArray::plus)
        return File(InstrumentationRegistry.getInstrumentation().context.cacheDir, name).apply { writeBytes(plain) }
    }

    /** The video track's format and how many frames Android's extractor reads from it. */
    private fun videoTrack(path: String): Pair<MediaFormat, Int> {
        val extractor = MediaExtractor().apply { setDataSource(path) }
        try {
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            extractor.selectTrack(track)
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            var n = 0
            while (extractor.readSampleData(buffer, 0) >= 0) {
                n++
                extractor.advance()
            }
            return extractor.getTrackFormat(track) to n
        } finally {
            extractor.release()
        }
    }
}
