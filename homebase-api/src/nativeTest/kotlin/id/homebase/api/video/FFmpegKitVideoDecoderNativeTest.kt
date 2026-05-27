@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package id.homebase.api.video

import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for the parts of [FFmpegKitVideoDecoder] that [TestFFmpegKitBridge] sidesteps by
 * running ffmpeg synchronously. Each test installs a purpose-built [FFmpegKitBridge] that
 * defers `onComplete` so the decoder's polling loop actually iterates — that's the path
 * carrying flow cancellation, [bridge.cancelAllFFmpegSessions] invocation, the
 * `withTimeoutOrNull(TEARDOWN_WAIT_MS)` await, and progressive frame emission.
 *
 * iOS-only test target (nativeTest). The cinterop with FFmpegKit only runs on macOS hosts —
 * Linux dev builds skip iosSimulatorArm64Test entirely; CI's macOS runner exercises it.
 */
class FFmpegKitVideoDecoderNativeTest {

    @AfterTest
    fun restoreProductionTestBridge() {
        // Each test installs its own bridge; restore the standard one so neighbours running
        // afterward in the same process see the bridge they expect.
        FFmpegKitBridgeHolder.setBridge(TestFFmpegKitBridge())
    }

    @Test
    fun extractThumbnailStrip_cancelsBridgeWhenFlowIsCancelled() = runBlocking {
        // Bridge that never fires onComplete — simulates a stuck/long-running ffmpeg session.
        val bridge = RecordingDeferredBridge()
        FFmpegKitBridgeHolder.setBridge(bridge)

        val fixturePath = stageFixtureOnDisk()
        try {
            val job = launch(Dispatchers.Default) {
                FFmpegKitVideoDecoder().extractThumbnailStrip(
                    videoPath = fixturePath,
                    durationMs = 6_000L,
                    frameCount = 10,
                    targetHeightPx = 96,
                ).toList()
            }

            // Give the channelFlow time to enter the poll loop and dispatch executeFFmpegAsync.
            // The poll is on 40 ms; 200 ms is plenty for at least one iteration on any runner.
            delay(200)
            assertTrue(bridge.executeAsyncCalled, "decoder must have started ffmpeg by now")
            assertEquals(0, bridge.cancelAllCalls, "no cancel yet — flow is still active")

            job.cancel()
            job.join()

            assertEquals(
                1, bridge.cancelAllCalls,
                "cancelling the flow must cancel the bridge session exactly once",
            )
        } finally {
            cleanup(fixturePath)
        }
    }

    @Test
    fun extractThumbnailStrip_drainsFramesProgressively() = runBlocking {
        // Bridge that captures the output pattern from the command, lets the test drop fake
        // JPEGs into the per-extraction dir while the "ffmpeg" run is still pending, then
        // completes successfully. Exercises the poll-and-drain code path that
        // `TestFFmpegKitBridge` (sync executeFFmpegAsync) never enters.
        val bridge = DriverBridge()
        FFmpegKitBridgeHolder.setBridge(bridge)

        val fixturePath = stageFixtureOnDisk()
        try {
            val framesAsync = async(Dispatchers.Default) {
                FFmpegKitVideoDecoder().extractThumbnailStrip(
                    videoPath = fixturePath,
                    durationMs = 6_000L,
                    frameCount = 3,
                    targetHeightPx = 96,
                ).toList()
            }

            // Wait for the command to reach the bridge and parse the output dir.
            val outDir = bridge.awaitOutDir()
            // Write 3 fake JPEGs into outDir at staggered intervals, simulating ffmpeg's
            // sequential writes. Each write should be picked up by the next poll cycle.
            for (i in 1..3) {
                withContext(Dispatchers.Default) {
                    writeFakeJpeg("$outDir/f_${i.toString().padStart(4, '0')}.jpg")
                    delay(60)  // > the decoder's 40 ms POLL_INTERVAL_MS
                }
            }
            bridge.completeSuccessfully()

            val frames = framesAsync.await()
            assertEquals(3, frames.size, "expected 3 frames; got ${frames.size}")
            assertEquals(listOf(0, 1, 2), frames.map { it.index })
            // Each frame body must be the fake-JPEG bytes we wrote (which are ≥ MIN_JPEG_BYTES).
            assertTrue(frames.all { it.jpegBytes.size >= 16 })
        } finally {
            cleanup(fixturePath)
        }
    }

    // ---- helpers -----------------------------------------------------------------------

    /** Writes [SampleVideoFixture.bytes] into NSCachesDirectory and returns the absolute path. */
    private fun stageFixtureOnDisk(): String {
        val cacheDir = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: NSTemporaryDirectory()
        val path = "$cacheDir/ffmpegkit_test_${NSUUID.UUID().UUIDString}.mp4"
        val bytes = SampleVideoFixture.bytes
        memScoped {
            val buffer = allocArrayOf(bytes)
            val data = NSData.dataWithBytes(buffer, bytes.size.toULong())
            check(data.writeToFile(path, true)) { "could not stage fixture at $path" }
        }
        return path
    }

    private fun cleanup(path: String) {
        runCatching { NSFileManager.defaultManager.removeItemAtPath(path, null) }
    }

    private fun writeFakeJpeg(path: String) {
        // 64 bytes starting with the JPEG SOI marker — enough to pass MIN_JPEG_BYTES.
        val bytes = ByteArray(64).apply {
            this[0] = 0xFF.toByte()
            this[1] = 0xD8.toByte()
        }
        memScoped {
            val buffer = allocArrayOf(bytes)
            val data = NSData.dataWithBytes(buffer, bytes.size.toULong())
            check(data.writeToFile(path, true)) { "could not write fake JPEG at $path" }
        }
    }
}

/**
 * A minimal [FFmpegKitBridge] that records cancel invocations and *never* completes the
 * pending session — to drive the cancellation-during-poll path.
 */
private class RecordingDeferredBridge : FFmpegKitBridge {
    @Volatile var executeAsyncCalled: Boolean = false
    @Volatile var cancelAllCalls: Int = 0

    override fun executeFFmpeg(command: String): FFmpegResult =
        FFmpegResult(isSuccess = false, failStackTrace = "unused in test")

    override fun executeFFmpegAsync(
        command: String,
        onProgress: (timeMs: Long) -> Unit,
        onComplete: (FFmpegResult) -> Unit,
    ) {
        executeAsyncCalled = true
        // Intentionally never invoke onComplete — simulate a long-running session.
    }

    override fun cancelAllFFmpegSessions() {
        cancelAllCalls++
    }

    override fun getMediaInformation(filePath: String): MediaInfo? = null

    override fun getFfmpegVersionBanner(): String? = null
}

/**
 * Bridge that captures the output directory from the ffmpeg command and exposes a hook
 * (`completeSuccessfully`) for the test to fire `onComplete` after staging fake frames.
 */
private class DriverBridge : FFmpegKitBridge {
    private val outDirReady = CompletableDeferred<String>()
    private var pendingCompletion: ((FFmpegResult) -> Unit)? = null

    suspend fun awaitOutDir(): String = outDirReady.await()

    fun completeSuccessfully() {
        pendingCompletion?.invoke(FFmpegResult(isSuccess = true, failStackTrace = null))
        pendingCompletion = null
    }

    override fun executeFFmpeg(command: String): FFmpegResult =
        FFmpegResult(isSuccess = false, failStackTrace = "unused in test")

    override fun executeFFmpegAsync(
        command: String,
        onProgress: (timeMs: Long) -> Unit,
        onComplete: (FFmpegResult) -> Unit,
    ) {
        // The command's output pattern is the last quoted token, of the form
        //   ".../vts_<uuid>/f_%04d.jpg"
        // Split on '"' and find the segment ending in `f_%04d.jpg`.
        val outPattern = command.split('"').firstOrNull { it.endsWith("f_%04d.jpg") }
            ?: error("could not find output pattern in: $command")
        val outDir = outPattern.substringBeforeLast("/f_%04d.jpg")
        outDirReady.complete(outDir)
        pendingCompletion = onComplete
    }

    override fun cancelAllFFmpegSessions() {
        pendingCompletion?.invoke(FFmpegResult(isSuccess = false, failStackTrace = "cancelled"))
        pendingCompletion = null
    }

    override fun getMediaInformation(filePath: String): MediaInfo? = null

    override fun getFfmpegVersionBanner(): String? = null
}
