package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.api.video.hls.Mp4Movie
import id.homebase.api.video.hls.Mp4Sample
import id.homebase.api.video.hls.Mp4Track
import id.homebase.api.video.hls.hlsFixture
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    private val source = hlsFixture(cache, "moov_first.mp4")

    private suspend fun movie() = assertNotNull(Mp4Movie.parse(assertNotNull(readMoov(fileOps, source.absolutePath))))

    private fun Mp4Movie.with(video: Mp4Track.Avc = this.video, audio: Mp4Track.Aac? = this.audio, samples: List<Mp4Sample> = this.samples) =
        Mp4Movie(video, audio, samples, start90k, end90k)

    @Test
    fun aSmallSdrH264WithAacLcMeetsEverySetting() = runTest {
        for (quality in listOf(VideoQuality.STANDARD, VideoQuality.HIGH)) assertTrue(meetsWithoutTranscode(movie(), quality), "$quality")
    }

    @Test
    fun hdrAProbeThatCantTellOrALargerFrameIsNoCandidate() {
        assertTrue(mightMeetWithoutTranscode(VideoTrackInfo("h264", 1280, 720, 8, isHdr = false), VideoQuality.STANDARD))
        assertTrue(mightMeetWithoutTranscode(VideoTrackInfo("h264", 0, 0, 8, isHdr = false), VideoQuality.STANDARD), "size unknown: the moov decides")
        assertFalse(mightMeetWithoutTranscode(VideoTrackInfo("h264", 1280, 720, 8, isHdr = true), VideoQuality.STANDARD))
        assertFalse(mightMeetWithoutTranscode(VideoTrackInfo("h264", 1280, 720, 8, isHdr = null), VideoQuality.STANDARD))
        assertFalse(mightMeetWithoutTranscode(VideoTrackInfo("h264", 1920, 1080, 8, isHdr = false), VideoQuality.STANDARD))
    }

    @Test
    fun aBitrateOverWhatTheEncoderWouldAskForIsTranscoded() = runTest {
        // About 50 kbps; a 320x180 transcode would ask for 156 kbps at Standard and 139 kbps at HD.
        val movie = movie()
        fun scaled(factor: Int) = movie.with(samples = movie.samples.map {
            if (it.track is Mp4Track.Avc) Mp4Sample(it.track, it.offset, it.size * factor, it.dts90k, it.pts90k, it.isKey) else it
        })
        for (quality in listOf(VideoQuality.STANDARD, VideoQuality.HIGH)) {
            assertTrue(meetsWithoutTranscode(scaled(2), quality), "$quality at about 100 kbps")
            assertFalse(meetsWithoutTranscode(scaled(4), quality), "$quality at about 200 kbps")
        }
    }

    @Test
    fun audioOtherThanStereoAacLcIsTranscoded() = runTest {
        val movie = movie()
        val aac = assertNotNull(movie.audio)
        assertFalse(meetsWithoutTranscode(movie.with(audio = Mp4Track.Aac(0, aac.sampleRateIndex, 1)), VideoQuality.STANDARD), "AAC Main")
        assertFalse(meetsWithoutTranscode(movie.with(audio = Mp4Track.Aac(1, aac.sampleRateIndex, 6)), VideoQuality.STANDARD), "5.1")
        assertTrue(meetsWithoutTranscode(movie.with(audio = null), VideoQuality.STANDARD), "no audio")
    }

    @Test
    fun nalLengthsOtherThanFourBytesAreTranscoded() = runTest {
        val movie = movie()
        assertFalse(meetsWithoutTranscode(movie.with(video = Mp4Track.Avc(2, movie.video.sps, movie.video.pps)), VideoQuality.STANDARD))
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
