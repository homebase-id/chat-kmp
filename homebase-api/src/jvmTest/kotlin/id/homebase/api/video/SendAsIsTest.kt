package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.api.video.hls.hlsFixture
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import org.junit.Test

/** The send path around [meetsWithoutTranscode], whose rule is tested on every platform in SendAsIsRuleTest. */
class SendAsIsTest {
    private val cache = Files.createTempDirectory("sendasis").toFile()
    private val fileOps = OkioFileOperationsProvider(FileSystem.SYSTEM, cache.absolutePath)
    private val source = hlsFixture(cache, "moov_first.mp4")

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
