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

/** Saving a received HLS video: our segmenter's output, decrypted as the download does, remuxed back to an MP4. */
@RunWith(AndroidJUnit4::class)
class RemuxHlsToMp4DeviceTest {
    private val keyHeader = KeyHeader(Random.nextBytes(16), SecureByteArray(Random.nextBytes(16)))
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        ActivityProvider.initializeApplicationContext(context)
    }

    @Test
    fun aLandscapeVideoComesBackWithEveryFrame() = runBlocking { check(asset("landscape_1080p_gps.mp4")) }

    @Test
    fun aPortraitVideoComesBackPortrait() = runBlocking { check(asset("portrait_1080p.mp4")) }

    /** Not a check unless run with `-e remuxBench true` and videos in the test app's files/bench. */
    @Test
    fun benchmark() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("remuxBench") != "true") return@runBlocking
        for (video in File(context.filesDir, "bench").listFiles { f -> f.extension == "mp4" }.orEmpty()) {
            repeat(2) { check(video, keep = true) }
        }
    }

    private fun asset(name: String): File {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        return File(context.cacheDir, name).apply { ctx.assets.open("test_videos/$name").use { i -> outputStream().use { i.copyTo(it) } } }
    }

    private suspend fun check(source: File, keep: Boolean = false) {
        val compressed = File(AndroidVideoCompressor.compress(source.absolutePath, null, null, null, VideoQuality.STANDARD, false))
        val segmented = assertNotNull(AndroidVideoCompressor.segmentAndEncrypt(compressed.absolutePath, keyHeader, null))
        val dir = File(context.cacheDir, "remux-${System.nanoTime()}").apply { mkdirs() }
        try {
            val ts = File(dir, "video.ts").apply { writeBytes(decrypted(segmented)) }
            // As the download writes it: no key, every segment line pointing at the one decrypted .ts.
            val playlist = File(dir, "video.m3u8").apply {
                writeText(File(segmented.playlistPath).readLines().filter { !it.startsWith("#EXT-X-KEY") }
                    .joinToString("\n") { if (it.isNotBlank() && !it.startsWith("#")) ts.name else it })
            }
            val ours = File(dir, "ours.mp4")
            val started = System.currentTimeMillis()
            assertTrue(AndroidVideoCompressor.remuxHlsToMp4(playlist.absolutePath, ours.absolutePath))
            val oursMs = System.currentTimeMillis() - started

            val expected = tracks(compressed)
            val got = tracks(ours)
            Log.i(
                "RemuxBenchmark",
                "${source.name} ${source.length() / 1000} kB: ${oursMs} ms ${ours.length() / 1000} kB $got; source $expected",
            )
            assertEquals(expected.size, got.size, "same frame size")
            assertEquals(expected.audio, got.audio, "audio kept")
            // Copied, not re-encoded: only in-band parameter sets come and go.
            assertTrue(kotlin.math.abs(got.videoBytes - expected.videoBytes) < expected.videoBytes / 100, "video bytes: $got against $expected")
            assertEquals(expected.frames, got.frames, "every frame")
        } finally {
            dir.deleteRecursively()
            compressed.delete()
            File(segmented.segmentsPath).parentFile?.deleteRecursively()
            if (!keep) source.delete()
        }
    }

    private suspend fun decrypted(segmented: SegmentedVideo): ByteArray {
        val stored = File(segmented.segmentsPath).readBytes()
        return Regex("#EXT-X-BYTERANGE:(\\d+)@(\\d+)").findAll(File(segmented.playlistPath).readText()).toList().map { m ->
            val (length, offset) = m.destructured
            AesCbc.decrypt(stored.copyOfRange(offset.toInt(), offset.toInt() + length.toInt()), keyHeader.aesKey, keyHeader.iv)
        }.reduce(ByteArray::plus)
    }

    private data class Tracks(val size: Pair<Int, Int>, val frames: Int, val audio: Boolean, val videoBytes: Long)

    private fun tracks(file: File): Tracks {
        val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
        try {
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            val format = formats[video]
            extractor.selectTrack(video)
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            var frames = 0
            var bytes = 0L
            while (true) {
                val n = extractor.readSampleData(buffer, 0)
                if (n < 0) break
                bytes += n
                frames++
                extractor.advance()
            }
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            return Tracks(if (rotation % 180 == 0) w to h else h to w, frames, formats.any { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }, bytes)
        } finally {
            extractor.release()
        }
    }
}
