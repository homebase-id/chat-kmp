package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.api.video.hls.Mp4Movie
import id.homebase.api.video.hls.Mp4Sample
import id.homebase.api.video.hls.Mp4Track
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import org.junit.Test

/**
 * When a video goes out without a transcode (THUMBNAILS.md, "Video"). The fixture is 320x180 8-bit H.264 at about
 * 50 kbps with mono AAC-LC, so it meets every setting as it is; each case changes one thing that must force a transcode.
 */
class SendAsIsTest {
    private val cache = Files.createTempDirectory("sendasis").toFile()
    private val fileOps = OkioFileOperationsProvider(FileSystem.SYSTEM, cache.absolutePath)
    private val source = File(cache, "moov_first.mp4").apply {
        SendAsIsTest::class.java.getResourceAsStream("/hls/moov_first.mp4")!!.use { i -> outputStream().use { i.copyTo(it) } }
    }

    private suspend fun movie() = assertNotNull(Mp4Movie.parse(assertNotNull(readMoov(fileOps, source.absolutePath))))

    @Test
    fun aSmallSdrH264WithAacLcMeetsEverySetting() = runTest {
        for (quality in listOf(VideoQuality.STANDARD, VideoQuality.HIGH)) assertTrue(meetsWithoutTranscode(movie(), isHdr = false, quality), "$quality")
    }

    @Test
    fun hdrOrAProbeThatCantTellIsTranscoded() = runTest {
        assertFalse(meetsWithoutTranscode(movie(), isHdr = true, VideoQuality.STANDARD))
        assertFalse(meetsWithoutTranscode(movie(), isHdr = null, VideoQuality.STANDARD))
    }

    @Test
    fun aBitrateOverTheSettingsTargetIsTranscoded() = runTest {
        val movie = movie()
        // The same frames, each 60 times the size: about 3 Mbps, over Standard's 2.5 and under HD's 5.
        val heavy = Mp4Movie(
            movie.video, movie.audio,
            movie.samples.map { if (it.track is Mp4Track.Avc) Mp4Sample(it.track, it.offset, it.size * 60, it.dts90k, it.pts90k, it.isKey) else it },
            movie.start90k, movie.end90k,
        )
        assertFalse(meetsWithoutTranscode(heavy, isHdr = false, VideoQuality.STANDARD))
        assertTrue(meetsWithoutTranscode(heavy, isHdr = false, VideoQuality.HIGH))
    }

    @Test
    fun audioOtherThanStereoAacLcIsTranscoded() = runTest {
        val movie = movie()
        val aac = assertNotNull(movie.audio)
        for (audio in listOf(Mp4Track.Aac(0, aac.sampleRateIndex, 1), Mp4Track.Aac(1, aac.sampleRateIndex, 6))) {
            assertFalse(meetsWithoutTranscode(Mp4Movie(movie.video, audio, movie.samples, movie.start90k, movie.end90k), isHdr = false, VideoQuality.STANDARD))
        }
        assertTrue(meetsWithoutTranscode(Mp4Movie(movie.video, null, movie.samples, movie.start90k, movie.end90k), isHdr = false, VideoQuality.STANDARD), "no audio")
    }

    @Test
    fun nalLengthsOtherThanFourBytesAreTranscoded() = runTest {
        val movie = movie()
        val video = Mp4Track.Avc(2, movie.video.sps, movie.video.pps)
        assertFalse(meetsWithoutTranscode(Mp4Movie(video, movie.audio, movie.samples, movie.start90k, movie.end90k), isHdr = false, VideoQuality.STANDARD))
    }

    @Test
    fun aRotatedVideoIsNeverAMovieToSendAsIs() = runTest {
        val rotated = File(cache, "rotated.mp4").apply {
            SendAsIsTest::class.java.getResourceAsStream("/hls/rotated.mp4")!!.use { i -> outputStream().use { i.copyTo(it) } }
        }
        assertNull(Mp4Movie.parse(assertNotNull(readMoov(fileOps, rotated.absolutePath))))
    }

    @Test
    fun aSendThatStripsMetadataRemuxesAVideoThatMeetsTheSettingWithoutTheCompressor() = runTest {
        val compressor = RecordingCompressor(source.absolutePath)
        val result = VideoPayloadProcessor(fileOps, compressor, SdrProbe).process(
            payload = PayloadFile(key = "vid", filePath = source.absolutePath),
            keyHeader = KeyHeader.newRandom16(),
            onProgress = null,
            descriptorContentPayloadKey = "descriptor",
            playlistPayloadKey = "pls_0000",
            videoQuality = VideoQuality.STANDARD,
        )
        assertEquals(0, compressor.calls, "a video that meets the setting isn't transcoded")
        assertEquals("video/mp4", result.videoMetadata.mimeType)
    }

    @Test
    fun aSendThatKeepsMetadataOrTrimsStillTranscodes() = runTest {
        for ((payload, trim) in listOf(
            PayloadFile(key = "vid", filePath = source.absolutePath, stripMetadata = false) to (null to null),
            PayloadFile(key = "vid", filePath = source.absolutePath) to (1_000L to 4_000L),
        )) {
            val compressor = RecordingCompressor(File(cache, "compressed.mp4").apply { writeBytes(source.readBytes()) }.absolutePath)
            VideoPayloadProcessor(fileOps, compressor, SdrProbe).process(
                payload = payload,
                keyHeader = KeyHeader.newRandom16(),
                onProgress = null,
                descriptorContentPayloadKey = "descriptor",
                playlistPayloadKey = "pls_0000",
                trimStartMs = trim.first,
                trimEndMs = trim.second,
                videoQuality = VideoQuality.STANDARD,
            )
            assertEquals(1, compressor.calls, "stripMetadata=${payload.stripMetadata} trim=$trim")
        }
    }

    private class RecordingCompressor(private val output: String) : VideoCompressor {
        var calls = 0

        override suspend fun compress(
            inputPath: String,
            onProgress: VideoProgressListener?,
            trimStartMs: Long?,
            trimEndMs: Long?,
            quality: VideoQuality,
            keepMetadata: Boolean,
        ): String {
            calls++
            return output
        }

        override suspend fun segmentAndEncrypt(inputPath: String, keyHeader: KeyHeader, onProgress: VideoProgressListener?): SegmentedVideo? = null
    }

    private object SdrProbe : VideoProber {
        override suspend fun getDurationMs(inputPath: String): Long = 14_000
        override suspend fun getFfmpegVersion(): String? = null
        override suspend fun probeVideo(inputPath: String) = VideoTrackInfo("h264", 320, 180, 8, isHdr = false)
        override fun canRead(contentType: String): Boolean = true
    }
}
