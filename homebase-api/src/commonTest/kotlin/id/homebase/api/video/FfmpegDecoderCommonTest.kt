package id.homebase.api.video

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One generic test for the ffmpeg backend that runs on every test target. Each platform's
 * `platformFfmpegDecoder()` actual returns its real ffmpeg decoder (FFmpegKit / subprocess /
 * ffmpeg.wasm) or null when none exists (Android — see [AndroidVideoDecoderFactory]).
 *
 * Skip semantics: a null [VideoThumbnailService.ffmpegDecoderForTest] OR a null
 * [stageSampleVideoForFfmpegTest] both cause the test to exit early and pass green — that's
 * how Android (no ffmpeg backend) and iOS / Web (no fixture wired up yet) report "nothing
 * meaningful to assert on this target."
 */
class FfmpegDecoderCommonTest {

    @Test
    fun extractPosterFrame_viaFfmpegDecoder() = runTest {
        val decoder = VideoThumbnailService.ffmpegDecoderForTest ?: return@runTest
        val path = stageSampleVideoForFfmpegTest() ?: return@runTest
        try {
            val bytes = decoder.extractPosterFrame(path)
            assertNotNull(bytes, "ffmpeg decoder must produce poster bytes for the fixture")
            assertTrue(bytes.size > 100, "poster bytes seem suspiciously small: ${bytes.size}")
            assertJpegMarker(bytes)
        } finally {
            cleanupStagedSampleVideo(path)
        }
    }

    @Test
    fun extractThumbnailStrip_viaFfmpegDecoder() = runTest {
        val decoder = VideoThumbnailService.ffmpegDecoderForTest ?: return@runTest
        val path = stageSampleVideoForFfmpegTest() ?: return@runTest
        try {
            val frames = decoder
                .extractThumbnailStrip(
                    videoPath = path,
                    durationMs = 6_000L,
                    frameCount = 5,
                    targetHeightPx = 96,
                )
                .toList()

            // `-frames:v N` may occasionally drop the trailing frame on very short clips when
            // fps math lands beyond duration. Tolerate >= N-1.
            assertTrue(
                frames.size in 4..5,
                "expected 4 or 5 strip frames, got ${frames.size}",
            )
            assertEquals(
                frames.size,
                frames.map { it.index }.toSet().size,
                "frame indices must be unique",
            )
            frames.forEach { f ->
                assertTrue(f.jpegBytes.size > 100, "frame ${f.index} bytes too small")
                assertJpegMarker(f.jpegBytes)
                assertTrue(f.timeMs >= 0L, "frame ${f.index} has negative timeMs")
            }
        } finally {
            cleanupStagedSampleVideo(path)
        }
    }

    private fun assertJpegMarker(bytes: ByteArray) {
        assertTrue(
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte(),
            "expected JPEG SOI marker (FFD8), got " +
                "0x${bytes.getOrNull(0)?.toUByte()?.toString(16) ?: "??"}" +
                "${bytes.getOrNull(1)?.toUByte()?.toString(16) ?: "??"}",
        )
    }
}
