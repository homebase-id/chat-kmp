package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [VideoPayloadProcessor] through fakes for the generic compression seam
 * ([VideoCompressor] / [VideoProber]) — the payoff of lifting `FFmpegUtils` behind
 * interfaces. No real ffmpeg runs for compress/segment/duration, so the HLS-vs-
 * direct branching is exercised deterministically.
 *
 * Phase 1 (poster frame) goes through `VideoThumbnailService.extractPosterFrame`, which
 * returns null in this classpath — the JVM decoder is `FFmpegSubprocessVideoDecoder`, whose
 * ffmpeg backend (`FFmpegBinaryManager`) has no bundled binaries here (they live in
 * homebase-chat). So the thumbnail phase is skipped and these tests focus on the
 * compress / HLS-vs-direct branching driven by the fakes below.
 */
class VideoPayloadProcessorCompressionSeamTest {

    private val cacheDir = "/cache"

    /** Minimal in-memory [FileOperationsProvider] backed by a path→bytes map. */
    private inner class FakeFileOperationsProvider(
        private val store: MutableMap<String, ByteArray>,
    ) : FileOperationsProvider {
        override fun openFileInput(path: String): InputProvider =
            throw UnsupportedOperationException("not used")

        override suspend fun readFileBytes(path: String): ByteArray =
            store[path] ?: error("missing file: $path")

        override fun deleteTempFile(path: String): Boolean = store.remove(path) != null

        override fun getCacheDirectory(): String = cacheDir

        override fun getFileSize(path: String): Long =
            store[path]?.size?.toLong() ?: error("missing file: $path")

        override suspend fun writeBytesToTempFile(
            bytes: ByteArray,
            prefix: String,
            suffix: String,
        ): String {
            val path = "$cacheDir/$prefix$suffix"
            store[path] = bytes
            return path
        }

        override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String =
            throw UnsupportedOperationException("not used")

        override suspend fun writeStream(path: String, data: Flow<ByteArray>) {
            val out = ArrayList<Byte>()
            data.collect { chunk -> chunk.forEach { out.add(it) } }
            store[path] = out.toByteArray()
        }
    }

    /** Records the calls it received and returns canned outputs. */
    private class FakeVideoCompressor(
        private val compressedPath: String,
        private val segmented: SegmentedVideo,
    ) : VideoCompressor {
        var compressInput: String? = null
        var compressQuality: VideoQuality? = null
        var compressTrim: Pair<Long?, Long?>? = null
        var segmentAndEncryptCalled = false

        override suspend fun compress(
            inputPath: String,
            onProgress: VideoProgressListener?,
            trimStartMs: Long?,
            trimEndMs: Long?,
            quality: VideoQuality,
            allowTenBit: Boolean,
        ): String? {
            compressInput = inputPath
            compressQuality = quality
            compressTrim = trimStartMs to trimEndMs
            // Simulate a chunky ffmpeg stream: ~1000 sub-percent ticks across 0f..1f.
            for (i in 0..1000) onProgress?.invoke(i / 1000f)
            return compressedPath
        }

        override suspend fun segment(inputPath: String, onProgress: VideoProgressListener?): SegmentedVideo =
            segmented

        override suspend fun segmentAndEncrypt(
            inputPath: String,
            keyHeader: KeyHeader,
            onProgress: VideoProgressListener?,
        ): SegmentedVideo {
            segmentAndEncryptCalled = true
            // Simulate a chunky ffmpeg stream: ~1000 sub-percent ticks across 0f..1f.
            for (i in 0..1000) onProgress?.invoke(i / 1000f)
            return segmented
        }

        override suspend fun remuxHlsToMp4(playlistPath: String, outputPath: String): Boolean = true

        override suspend fun cacheInputVideo(fileName: String, data: ByteArray): String =
            "$fileName-cached"
    }

    private class FakeVideoProbe(private val durationMs: Long) : VideoProber {
        override suspend fun getDurationMs(inputPath: String): Long = durationMs
        override suspend fun getFfmpegVersion(): String? = "test"
        override suspend fun probeVideo(inputPath: String): VideoTrackInfo? = null
    }

    @Test
    fun smallCompressedVideoTakesDirectEncryptPathNotHls() = runTest {
        val inputPath = "$cacheDir/input.mp4"
        val compressedPath = "$cacheDir/compressed.mp4"
        // < 5 MB → no HLS segmentation.
        val store = mutableMapOf(
            inputPath to ByteArray(1024),
            compressedPath to ByteArray(1024),
        )
        val fileOps = FakeFileOperationsProvider(store)
        val compressor = FakeVideoCompressor(
            compressedPath = compressedPath,
            segmented = SegmentedVideo("$cacheDir/playlist.m3u8", "$cacheDir/segments.ts"),
        )
        val processor = VideoPayloadProcessor(fileOps, compressor, FakeVideoProbe(12_345L))

        val result = processor.process(
            payload = PayloadFile(key = "vid", filePath = inputPath),
            keyHeader = KeyHeader.newRandom16(),
            onProgress = null,
            descriptorContentPayloadKey = "descriptor",
            trimStartMs = 1_000L,
            trimEndMs = 4_000L,
            videoQuality = VideoQuality.HIGH,
        )

        // The seam was used, with the caller's args passed straight through.
        assertEquals(inputPath, compressor.compressInput)
        assertEquals(VideoQuality.HIGH, compressor.compressQuality)
        assertEquals(1_000L to 4_000L, compressor.compressTrim)

        // Direct (non-HLS) path: no segmentation, mp4 mime, duration from the probe.
        assertFalse(compressor.segmentAndEncryptCalled)
        assertFalse(result.videoMetadata.isSegmented)
        assertEquals("video/mp4", result.videoMetadata.mimeType)
        assertNull(result.videoMetadata.hlsPlaylist)
        assertEquals(12_345f, result.videoMetadata.duration)
    }

    @Test
    fun largeCompressedVideoTakesHlsSegmentationPath() = runTest {
        val inputPath = "$cacheDir/input.mp4"
        val compressedPath = "$cacheDir/compressed.mp4"
        val playlistPath = "$cacheDir/playlist.m3u8"
        val segmentsPath = "$cacheDir/segments.ts"
        val playlistText = "#EXTM3U\n#EXT-X-VERSION:3\n"
        // >= 5 MB → HLS segmentation.
        val store = mutableMapOf(
            inputPath to ByteArray(1024),
            compressedPath to ByteArray(5 * 1024 * 1024),
            playlistPath to playlistText.encodeToByteArray(),
            segmentsPath to ByteArray(2048),
        )
        val fileOps = FakeFileOperationsProvider(store)
        val compressor = FakeVideoCompressor(
            compressedPath = compressedPath,
            segmented = SegmentedVideo(playlistPath, segmentsPath),
        )
        val processor = VideoPayloadProcessor(fileOps, compressor, FakeVideoProbe(9_000L))

        val result = processor.process(
            payload = PayloadFile(key = "vid", filePath = inputPath),
            keyHeader = KeyHeader.newRandom16(),
            onProgress = null,
            descriptorContentPayloadKey = "descriptor",
        )

        assertTrue(compressor.segmentAndEncryptCalled)
        assertTrue(result.videoMetadata.isSegmented)
        assertEquals("application/vnd.apple.mpegurl", result.videoMetadata.mimeType)
        assertEquals(playlistText, result.videoMetadata.hlsPlaylist)
        assertEquals(9_000f, result.videoMetadata.duration)
    }

    @Test
    fun videoProgressIsDeduplicatedToWholePercentPerPhase() = runTest {
        val inputPath = "$cacheDir/input.mp4"
        val compressedPath = "$cacheDir/compressed.mp4"
        val playlistPath = "$cacheDir/playlist.m3u8"
        val segmentsPath = "$cacheDir/segments.ts"
        val playlistText = "#EXTM3U\n#EXT-X-VERSION:3\n"
        // >= 5 MB → HLS, so both compress and segmentAndEncrypt run (and fire progress ticks).
        val store = mutableMapOf(
            inputPath to ByteArray(1024),
            compressedPath to ByteArray(5 * 1024 * 1024),
            playlistPath to playlistText.encodeToByteArray(),
            segmentsPath to ByteArray(2048),
        )
        val fileOps = FakeFileOperationsProvider(store)
        val compressor = FakeVideoCompressor(
            compressedPath = compressedPath,
            segmented = SegmentedVideo(playlistPath, segmentsPath),
        )
        val processor = VideoPayloadProcessor(fileOps, compressor, FakeVideoProbe(9_000L))

        val events = mutableListOf<VideoPayloadProgressPhase>()
        processor.process(
            payload = PayloadFile(key = "vid", filePath = inputPath),
            keyHeader = KeyHeader.newRandom16(),
            onProgress = { events.add(it) },
            descriptorContentPayloadKey = "descriptor",
        )

        // The fake fired ~1000 sub-percent ticks per phase; the processor must collapse each
        // phase to at most one event per whole integer percent — no per-tick duplicates.
        val compressing = events
            .filter { it.phase == VideoProcessingPhase.COMPRESSING }
            .map { (it.progress * 100).toInt() }
        assertTrue(compressing.isNotEmpty(), "expected COMPRESSING progress")
        assertEquals(compressing, compressing.distinct(), "COMPRESSING emitted duplicate whole percents")
        assertTrue(compressing.size <= 101, "COMPRESSING emitted ${compressing.size} events (expected <= 101)")

        val segmenting = events
            .filter { it.phase == VideoProcessingPhase.SEGMENTING }
            .map { (it.progress * 100).toInt() }
        assertTrue(segmenting.isNotEmpty(), "expected SEGMENTING progress on the HLS path")
        assertEquals(segmenting, segmenting.distinct(), "SEGMENTING emitted duplicate whole percents")
        assertTrue(segmenting.size <= 101, "SEGMENTING emitted ${segmenting.size} events (expected <= 101)")
    }
}
