package id.homebase.api.video

import id.homebase.api.media.MediaLocation
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.homebase.api.ActivityProvider
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.crypto.encryptWithPayloadHash
import id.homebase.api.file.AndroidFileOperationsProvider
import id.homebase.api.client.KeyHeader
import id.homebase.api.common.SecureByteArray
import id.homebase.api.image.MediaQuality
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** [Media3VideoCompressor] on a real device. */
@RunWith(AndroidJUnit4::class)
class Media3VideoCompressorTest {
    private companion object {
        const val COOL_DOWN_MS = 30_000L
    }

    @Before
    fun setUp() {
        ActivityProvider.initializeApplicationContext(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun aLandscape1080pVideoComesOutAt720pH264WithoutItsLocation() = runBlocking {
        val out = compress("landscape_1080p_gps.mp4", VideoQuality.STANDARD, keepMetadata = false)
        val info = probe(out)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, info.mime)
        assertEquals(1280 to 720, info.displaySize)
        assertTrue(abs(info.durationMs - 3_000) < 200, "duration ${info.durationMs}")
        assertNull(info.location, "a stripped video must not carry its location")
    }

    @Test
    fun aGalleryPickIsCompressedWhereItIsWithItsLocation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val camera = withCameraLocation(fixture("landscape_1080p_gps.mp4"))
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "in_place_${System.nanoTime()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            },
        )!!
        try {
            resolver.openOutputStream(uri)!!.use { out -> camera.inputStream().use { it.copyTo(out) } }
            camera.delete()
            assertTrue(AndroidVideoCompressor.readsInPlace(uri.toString()), "no cache copy is made for it")
            val before = context.cacheDir.walk().filter { it.name.startsWith("resolved_") }.count()
            val poster = assertNotNull(VideoThumbnailService.extractPosterFrame(uri.toString(), fullSize = true))
            val posterSize = BitmapFactory.decodeByteArray(poster, 0, poster.size).let { maxOf(it.width, it.height) }
            assertEquals(1920, posterSize, "an upload's poster is the frame at the video's own size")

            val info = probe(AndroidVideoCompressor.compress(uri.toString(), null, null, null, VideoQuality.STANDARD, keepMetadata = true))

            assertEquals(1280 to 720, info.displaySize)
            val location = info.location?.let(MediaLocation::parse)
            assertTrue(location != null && abs(location.latitude - 59.9139) < 1e-4 && abs(location.longitude - 10.7522) < 1e-4, "location ${info.location}")
            assertEquals(before, context.cacheDir.walk().filter { it.name.startsWith("resolved_") }.count(), "nothing was copied")
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun anUploadsPosterOfA5kVideoIs4096Wide() = runBlocking {
        val poster = assertNotNull(VideoThumbnailService.extractPosterFrame(fixture("poster_5k.mp4").absolutePath, fullSize = true))
        val bitmap = BitmapFactory.decodeByteArray(poster, 0, poster.size)
        assertEquals(4096 to 2304, bitmap.width to bitmap.height)
    }

    @Test
    fun keepingMetadataKeepsTheLocation() = runBlocking {
        val out = compress("landscape_1080p_gps.mp4", VideoQuality.STANDARD, keepMetadata = true)
        val info = probe(out)
        val location = info.location?.let(MediaLocation::parse)
        assertTrue(location != null && abs(location.latitude - 59.9139) < 1e-4 && abs(location.longitude - 10.7522) < 1e-4, "location ${info.location}")
    }

    @Test
    fun theCaptureTimeIsKeptOnlyWhenMetadataIs() = runBlocking {
        fun dateAfter(keep: Boolean) = runBlocking {
            val input = fixture("landscape_1080p_gps.mp4")
            probe(Media3VideoCompressor.compress(input.absolutePath, null, null, null, VideoQuality.LOW, keep)).date.also { input.delete() }
        }
        assertEquals("20260102T030405.000Z", dateAfter(keep = true))
        assertTrue(dateAfter(keep = false)?.startsWith("20260102") != true, "a stripped video must not carry its capture time")
    }

    @Test
    fun aacAudioIsCopiedAndOtherAudioBecomesAac() = runBlocking {
        val source = fixture("sample.mp4")
        val sourceAudio = audioFormat(source.absolutePath).also { source.delete() }
        val copied = compress("sample.mp4", VideoQuality.STANDARD, keepMetadata = false).let { out -> audioFormat(out).also { File(out).delete() } }
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, copied?.getString(MediaFormat.KEY_MIME))
        assertEquals(sourceAudio?.getByteBuffer("csd-0"), copied?.getByteBuffer("csd-0"), "copied AAC keeps its codec config")
        val reencoded = compress("opus_audio.mp4", VideoQuality.STANDARD, keepMetadata = false).let { out -> audioFormat(out).also { File(out).delete() } }
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, reencoded?.getString(MediaFormat.KEY_MIME))
    }

    @Test
    fun eachQualitySettingGivesItsSizeAndAboutItsBitrate() = runBlocking {
        // A 3 s 1440p60 clip without audio, so the file is the video stream alone. On content this
        // hard the hardware encoder overshoots, hence the wide band.
        val expected = mapOf(
            MediaQuality.STANDARD to (1280 to 720),
            MediaQuality.HD to (1920 to 1080),
            MediaQuality.ORIGINAL to (1920 to 1080),
        )
        for ((setting, size) in expected) {
            val quality = setting.toVideoQuality()
            val source = fixture("landscape_1440p.mp4")
            val media3 = Media3VideoCompressor.compress(source.absolutePath, null, null, null, quality, false)
            source.delete()
            val media3Bytes = File(media3).length()
            val targetBytes = quality.targets().videoBitrateBps.toLong() * AndroidVideoProber.getDurationMs(media3) / 8000
            val media3Size = probe(media3).displaySize
            Log.i("Media3Quality", "$setting -> $quality: media3 $media3Size ${media3Bytes / 1000} kB, target ${targetBytes / 1000} kB")
            assertEquals(size, media3Size, "$setting media3")
            assertTrue(media3Bytes in targetBytes / 2..targetBytes * 2, "$setting: $media3Bytes bytes against a target of $targetBytes")
        }
    }

    @Test
    fun aSmallVideoGetsABitrateForItsSize() = runBlocking {
        val out = compress("sample.mp4", VideoQuality.STANDARD, keepMetadata = false)
        val bytes = File(out).length()
        probe(out)
        // Standard's full 2.5 Mbps for these 6 s would be about 1.9 MB.
        assertTrue(bytes < 700_000, "$bytes bytes")
    }

    @Test
    fun aPortraitVideoStaysPortrait() = runBlocking {
        val out = compress("portrait_1080p.mp4", VideoQuality.STANDARD, keepMetadata = false)
        assertEquals(720 to 1280, probe(out).displaySize)
    }

    @Test
    fun anHdrVideoComesOutAsSdrH264() = runBlocking {
        val out = compress("hdr10_720p_short.mp4", VideoQuality.HIGH, keepMetadata = false)
        val info = probe(out)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, info.mime)
        assertTrue(info.colorTransfer != MediaFormat.COLOR_TRANSFER_ST2084 && info.colorTransfer != MediaFormat.COLOR_TRANSFER_HLG, "transfer ${info.colorTransfer}")
        assertEquals(720 to 1280, info.displaySize, "720p is under HD's 1080 short side, so it isn't scaled")
    }

    @Test
    fun aTrimKeepsOnlyTheRange() = runBlocking {
        val out = compress("landscape_1080p_gps.mp4", VideoQuality.LOW, keepMetadata = false, trim = 500L to 2_500L)
        val info = probe(out)
        assertTrue(abs(info.durationMs - 2_000) < 200, "duration ${info.durationMs}")
        assertEquals(854 to 480, info.displaySize)
    }

    @Test
    fun progressIsReportedAndEndsAtOne() = runBlocking {
        val fixture = fixture("landscape_1080p_gps.mp4")
        val reported = mutableListOf<Float>()
        val out = AndroidVideoCompressor.compress(fixture.absolutePath, { reported += it }, null, null, VideoQuality.STANDARD, false)
        File(out).delete()
        assertEquals(1f, reported.last())
        assertTrue(reported.all { it in 0f..1f }, "$reported")
    }

    @Test
    fun aCancelledCompressStopsWithinOneSecond() = runBlocking {
        val long = File(fixture("landscape_1080p_gps.mp4").parentFile, "landscape_x60.mp4")
        repeatInto(fixture("landscape_1080p_gps.mp4"), long, times = 60)
        val compress = launch(Dispatchers.IO) {
            Media3VideoCompressor.compress(long.absolutePath, null, null, null, VideoQuality.STANDARD, false)
        }
        delay(2_000L)
        assertTrue(compress.isActive, "the 3-minute encode must still be running when it's cancelled")
        val stopping = TimeSource.Monotonic.markNow()
        compress.cancelAndJoin()
        val took = stopping.elapsedNow()
        long.delete()
        assertTrue(took < 1.seconds, "cancel took $took")
    }

    /**
     * Not a check: what sending a video costs end to end, as VideoPayloadProcessor runs it, at Standard and HD
     * (Original video is HD's transcode outside Photos). Compress, then
     * HLS (segment and encrypt) at 5 MB or more, else encrypt the MP4 in one pass, with the payload hash.
     */
    @Test
    fun benchmarkEndToEnd() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fileOps = AndroidFileOperationsProvider(context)
        for (input in benchInputs()) for (quality in listOf(VideoQuality.STANDARD, VideoQuality.HIGH)) {
            repeat(2) {
                run {
                    delay(COOL_DOWN_MS)
                    val key = KeyHeader(Random.nextBytes(16), SecureByteArray(Random.nextBytes(16)))
                    val started = System.currentTimeMillis()
                    val compressed = AndroidVideoCompressor.compress(input.absolutePath, null, null, null, quality, false)
                    val compressMs = System.currentTimeMillis() - started
                    val compressedBytes = File(compressed).length()
                    val hls = compressedBytes >= HLS_MIN_BYTES
                    val second = System.currentTimeMillis()
                    val output = if (hls) {
                        File(AndroidVideoCompressor.segmentAndEncrypt(compressed, key, null)!!.segmentsPath).parentFile!!
                    } else {
                        val out = File(context.cacheDir, "encrypted.bin")
                        fileOps.encryptWithPayloadHash(compressed, out.absolutePath, key, PayloadHashAlgorithm.Blake3)
                        out
                    }
                    val now = System.currentTimeMillis()
                    Log.i(
                        "Media3Benchmark",
                        "${input.name} ${input.length() / 1_000_000}MB $quality end to end: ${now - started}ms " +
                            "(compress ${compressMs}ms to ${compressedBytes / 1000}kB, ${if (hls) "HLS" else "MP4 encrypt"} ${now - second}ms)",
                    )
                    output.deleteRecursively()
                    File(compressed).delete()
                }
            }
        }
    }

    private fun audioFormat(path: String): MediaFormat? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        } finally {
            extractor.release()
        }
    }

    // The app's own bench/ folders: external for adb push on newer Android, internal (via run-as) everywhere.
    private fun benchInputs(): List<File> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
            .flatMap { File(it, "bench").listFiles { f -> f.extension.lowercase() in setOf("mp4", "mov") }.orEmpty().toList() }
    }

    private suspend fun timed(block: suspend () -> String): Pair<Long, Long> {
        val started = System.currentTimeMillis()
        val out = File(block())
        val result = (System.currentTimeMillis() - started) to out.length()
        out.delete()
        return result
    }

    private suspend fun compress(name: String, quality: VideoQuality, keepMetadata: Boolean, trim: Pair<Long, Long>? = null): String {
        val input = if (name == "landscape_1080p_gps.mp4") withCameraLocation(fixture(name)) else fixture(name)
        val out = Media3VideoCompressor.compress(input.absolutePath, null, trim?.first, trim?.second, quality, keepMetadata)
        input.delete()
        return out
    }

    private fun fixture(name: String): File {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val out = File(ctx.cacheDir, name)
        ctx.assets.open("test_videos/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private data class Probe(val mime: String?, val displaySize: Pair<Int, Int>, val durationMs: Long, val colorTransfer: Int?, val location: String?, val date: String?)

    private fun probe(path: String): Probe {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            extractor.setDataSource(path)
            retriever.setDataSource(path)
            val video = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            val w = video.getInteger(MediaFormat.KEY_WIDTH)
            val h = video.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            return Probe(
                mime = video.getString(MediaFormat.KEY_MIME),
                displaySize = if (rotation % 180 == 0) w to h else h to w,
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0,
                colorTransfer = if (video.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) video.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else null,
                location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION),
                date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
            )
        } finally {
            extractor.release()
            retriever.release()
            File(path).delete()
        }
    }

    /** Re-muxes [source] with the location written as a phone camera writes it (`©xyz`). */
    private fun withCameraLocation(source: File): File {
        val target = File(source.parentFile, "camera_${source.name}")
        repeatInto(source, target, times = 1, location = 59.9139f to 10.7522f)
        return target
    }

    /** Copies [source]'s samples [times] times end to end, without re-encoding. */
    private fun repeatInto(source: File, target: File, times: Int, location: Pair<Float, Float>? = null) {
        val extractor = MediaExtractor().apply { setDataSource(source.absolutePath) }
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        location?.let { (lat, lon) -> muxer.setLocation(lat, lon) }
        val tracks = (0 until extractor.trackCount).associateWith { muxer.addTrack(extractor.getTrackFormat(it)) }
        tracks.keys.forEach { extractor.selectTrack(it) }
        muxer.start()
        val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        val durationUs = extractor.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION) + 50_000
        repeat(times) { round ->
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime + round * durationUs, extractor.sampleFlags)
                muxer.writeSampleData(tracks.getValue(extractor.sampleTrackIndex), buffer, info)
                extractor.advance()
            }
        }
        muxer.stop()
        muxer.release()
        extractor.release()
        source.delete()
    }
}
