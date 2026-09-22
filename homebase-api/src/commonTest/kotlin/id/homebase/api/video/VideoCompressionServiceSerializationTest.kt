package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test for the **serialization invariant** [VideoCompressionService] enforces:
 * the shared ffmpeg backend is not concurrency-safe, so two heavy ops (compress / segment /
 * segmentAndEncrypt / remux / transcode) must never run at the same time. The service guards them behind
 * one [kotlinx.coroutines.sync.Mutex]; this test proves a second call blocks until the first
 * releases instead of entering the backend concurrently.
 *
 * Without the mutex, both launched coroutines would enter the fake's body before the first
 * one finishes (`maxConcurrent == 2`); with it, the second waits (`maxConcurrent == 1`).
 *
 * Runs on JVM CI (and every other target) since it uses only a fake [VideoCompressor] — no
 * real ffmpeg. The companion against real ffmpeg is [FFmpegCompressionCommonTest].
 */
class VideoCompressionServiceSerializationTest {

    private val original: VideoCompressor = VideoCompressionService.compressor

    @AfterTest
    fun restoreDelegate() {
        VideoCompressionService.compressor = original
    }

    @Test
    fun compress_secondCallWaitsForFirstToRelease() = runTest {
        // A fake whose compress() parks on `gate` while holding the service's lock, so we can
        // observe whether a concurrent caller slips into the body. Tracks peak concurrency.
        val gate = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        var active = 0
        var maxConcurrent = 0
        var totalEntries = 0

        VideoCompressionService.compressor = object : NoopVideoCompressor() {
            override suspend fun compress(
                inputPath: String,
                onProgress: VideoProgressListener?,
                trimStartMs: Long?,
                trimEndMs: Long?,
                quality: VideoQuality,
            ): String {
                active++
                totalEntries++
                if (active > maxConcurrent) maxConcurrent = active
                if (!firstEntered.isCompleted) firstEntered.complete(Unit)
                gate.await()
                active--
                return "out_$inputPath"
            }
        }

        val j1 = launch {
            VideoCompressionService.compress("a", quality = VideoQuality.STANDARD)
        }
        val j2 = launch {
            VideoCompressionService.compress("b", quality = VideoQuality.STANDARD)
        }

        // First call is now inside the body holding the lock; let the scheduler give the
        // second call its chance to (try to) enter. With the mutex it must block.
        firstEntered.await()
        testScheduler.advanceUntilIdle()
        assertEquals(
            1, maxConcurrent,
            "the second compress entered the backend while the first still held the lock",
        )

        // Release the first; the second should now run (proving it wasn't dropped).
        gate.complete(Unit)
        j1.join()
        j2.join()

        assertEquals(1, maxConcurrent, "peak concurrency must stay 1 across the whole run")
        assertEquals(2, totalEntries, "both compress calls must eventually run")
    }

    @Test
    fun transcode_waitsForAnInFlightCompress() = runTest {
        val gate = CompletableDeferred<Unit>()
        val compressEntered = CompletableDeferred<Unit>()
        var transcodeEntered = false

        VideoCompressionService.compressor = object : NoopVideoCompressor() {
            override suspend fun compress(
                inputPath: String,
                onProgress: VideoProgressListener?,
                trimStartMs: Long?,
                trimEndMs: Long?,
                quality: VideoQuality,
            ): String {
                compressEntered.complete(Unit)
                gate.await()
                return "out_$inputPath"
            }

            override suspend fun transcode(input: ByteArray, extension: String, outputArgs: List<String>): ByteArray? {
                transcodeEntered = true
                return input
            }
        }

        val compress = launch { VideoCompressionService.compress("a", quality = VideoQuality.STANDARD) }
        compressEntered.await()
        val transcode = launch { VideoCompressionService.transcode(ByteArray(1), "gif", emptyList()) }
        testScheduler.advanceUntilIdle()
        assertFalse(transcodeEntered, "transcode entered the backend while a compress held the lock")

        gate.complete(Unit)
        compress.join()
        transcode.join()
        assertTrue(transcodeEntered)
    }
}

/** A [VideoCompressor] whose non-`compress` members are unused in serialization tests. */
private open class NoopVideoCompressor : VideoCompressor {
    override suspend fun compress(
        inputPath: String,
        onProgress: VideoProgressListener?,
        trimStartMs: Long?,
        trimEndMs: Long?,
        quality: VideoQuality,
    ): String = "out_$inputPath"

    override suspend fun segment(
        inputPath: String,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo? = null

    override suspend fun segmentAndEncrypt(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo? = null

    override suspend fun remuxHlsToMp4(playlistPath: String, outputPath: String): Boolean = false

    override suspend fun transcode(input: ByteArray, extension: String, outputArgs: List<String>): ByteArray? = null

    override suspend fun cacheInputVideo(fileName: String, data: ByteArray): String = ""
}
