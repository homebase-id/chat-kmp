package id.homebase.api.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.homebase.api.ActivityProvider
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.assertEquals
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

/** [Media3VideoCompressor] on a real device: the same output contract as the ffmpeg path. */
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
    fun keepingMetadataKeepsTheLocation() = runBlocking {
        val out = compress("landscape_1080p_gps.mp4", VideoQuality.STANDARD, keepMetadata = true)
        val info = probe(out)
        val location = info.location?.let(VideoLocation::parse)
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
    fun aSmallVideoGetsABitrateForItsSize() = runBlocking {
        val out = compress("sample.mp4", VideoQuality.STANDARD, keepMetadata = false)
        val bytes = File(out).length()
        probe(out)
        // 320x180 for 6 s. A Pixel 8 gave 912 kB at Standard's full 2.5 Mbps and 519 kB with the
        // bitrate scaled (its encoder doesn't go lower on so small a picture); ffmpeg gave 426 kB.
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
     * Not a check: times Media3 against ffmpeg on videos pushed to the test app's own folder, e.g.
     * `adb push big.mp4 /sdcard/Android/data/id.homebase.api.test/files/bench/`. Does nothing when it's empty.
     * Runs alternate which goes first and pause between, as the phone throttles when hot.
     */
    @Test
    fun benchmarkAgainstFfmpeg() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val inputs = File(context.getExternalFilesDir(null), "bench").listFiles { f -> f.extension.lowercase() in setOf("mp4", "mov") }.orEmpty()
        if (inputs.isEmpty()) return@runBlocking
        var media3First = true
        for (input in inputs.sortedBy { it.length() }) {
            for (quality in listOf(VideoQuality.STANDARD, VideoQuality.HIGH)) {
                val runs = listOf<Pair<String, suspend () -> String>>(
                    "media3" to { Media3VideoCompressor.compress(input.absolutePath, null, null, null, quality, true) },
                    "ffmpeg" to { FFmpegUtils.compressVideo(input.absolutePath, null, null, null, quality, true) },
                ).let { if (media3First) it else it.reversed() }
                media3First = !media3First
                for ((name, run) in runs) {
                    delay(COOL_DOWN_MS)
                    val thermal = power.currentThermalStatus
                    val (ms, bytes) = timed(run)
                    Log.i("Media3Benchmark", "${input.name} ${input.length() / 1_000_000}MB $quality $name: ${ms}ms ${bytes / 1000}kB thermal=$thermal")
                }
            }
            delay(COOL_DOWN_MS)
            val audio = File(input.parentFile, "audio_only.m4a")
            val started = System.currentTimeMillis()
            val session = executeFfmpeg(arrayOf("-y", "-i", input.absolutePath, "-vn", "-c:a", "aac", "-b:a", "128k", audio.absolutePath))
            Log.i("Media3Benchmark", "${input.name} ffmpeg audio only: ${System.currentTimeMillis() - started}ms rc=${session.returnCode} thermal=${power.currentThermalStatus}")
            audio.delete()
            delay(COOL_DOWN_MS)
            androidAacReencodeMs(input)?.let { ms ->
                Log.i("Media3Benchmark", "${input.name} Android AAC re-encode only: ${ms}ms thermal=${power.currentThermalStatus}")
            }
        }
    }

    /** Decodes and re-encodes [input]'s audio with Android's own AAC codecs, the step Media3 avoids by copying. */
    private fun androidAacReencodeMs(input: File): Long? {
        val extractor = MediaExtractor().apply { setDataSource(input.absolutePath) }
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC }
            ?: return null.also { extractor.release() }
        extractor.selectTrack(track)
        val source = extractor.getTrackFormat(track)
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(source, null, null, 0); start() }
        val target = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, source.getInteger(MediaFormat.KEY_SAMPLE_RATE), source.getInteger(MediaFormat.KEY_CHANNEL_COUNT)).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, 2)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(target, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
        val info = MediaCodec.BufferInfo()
        val pcm = ArrayDeque<Pair<ByteArray, Long>>()
        var inputDone = false
        var decoded = false
        var encoderEos = false
        val started = System.currentTimeMillis()
        while (true) {
            if (!inputDone) {
                val i = decoder.dequeueInputBuffer(0)
                if (i >= 0) {
                    val n = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                    if (n < 0) {
                        decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            if (!decoded) {
                val o = decoder.dequeueOutputBuffer(info, 0)
                if (o >= 0) {
                    val bytes = ByteArray(info.size).also { decoder.getOutputBuffer(o)!!.get(it) }
                    pcm.addLast(bytes to info.presentationTimeUs)
                    decoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoded = true
                }
            }
            if (pcm.isNotEmpty() || (decoded && !encoderEos)) {
                val i = encoder.dequeueInputBuffer(0)
                if (i >= 0) {
                    if (pcm.isEmpty()) {
                        encoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encoderEos = true
                    } else {
                        val buffer = encoder.getInputBuffer(i)!!
                        val (bytes, timeUs) = pcm.removeFirst()
                        val n = minOf(bytes.size, buffer.remaining())
                        buffer.put(bytes, 0, n)
                        if (n < bytes.size) pcm.addFirst(bytes.copyOfRange(n, bytes.size) to timeUs)
                        encoder.queueInputBuffer(i, 0, n, timeUs, 0)
                    }
                }
            }
            val o = encoder.dequeueOutputBuffer(info, 0)
            if (o >= 0) {
                encoder.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        val ms = System.currentTimeMillis() - started
        decoder.release()
        encoder.release()
        extractor.release()
        return ms
    }

    private suspend fun executeFfmpeg(args: Array<String>): com.arthenica.ffmpegkit.FFmpegSession =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            com.arthenica.ffmpegkit.FFmpegKit.executeWithArgumentsAsync(args) { session -> cont.resumeWith(Result.success(session)) }
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
