@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package id.homebase.api.video

import kotlin.concurrent.Volatile
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
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
        // Bridge captures the per-extraction outDir from the command and defers onComplete
        // until the test releases it. The test writes a fake JPEG, then awaits exactly that
        // index from the decoder's emission channel before writing the next — turning the
        // poll-loop drain into a deterministic per-emission handoff. No `delay(N)` races.
        val bridge = DriverBridge()
        FFmpegKitBridgeHolder.setBridge(bridge)

        val fixturePath = stageFixtureOnDisk()
        val emissions = Channel<IndexedFrame>(Channel.UNLIMITED)
        try {
            val collectJob = launch(Dispatchers.Default) {
                FFmpegKitVideoDecoder()
                    .extractThumbnailStrip(
                        videoPath = fixturePath,
                        durationMs = 6_000L,
                        frameCount = 3,
                        targetHeightPx = 96,
                    )
                    .collect { emissions.send(it) }
                emissions.close()
            }

            val outDir = bridge.awaitOutDir()

            // Write each fake JPEG, then wait — with a generous timeout — for the decoder's
            // poll loop to pick it up and emit. The timeout dwarfs the 40 ms poll interval,
            // so we'd only hit it if the drain path was genuinely broken (the failure mode
            // we want to detect). No "did the poll happen to land between writes?" race.
            for (i in 0 until 3) {
                writeFakeJpeg("$outDir/f_${(i + 1).toString().padStart(4, '0')}.jpg")
                val emitted = withTimeout(5_000) { emissions.receive() }
                assertEquals(i, emitted.index, "expected emission index $i, got ${emitted.index}")
                assertTrue(
                    emitted.jpegBytes.size >= 16,
                    "frame $i bytes too small (${emitted.jpegBytes.size})",
                )
            }

            bridge.completeSuccessfully()
            // collectJob.join() blocks until the collect lambda runs `emissions.close()`,
            // so reaching this line proves the flow terminated cleanly. No need for a
            // separate `isClosedForReceive` assertion — and that property is marked
            // @DelicateCoroutinesApi precisely because it has race-condition caveats under
            // concurrent sends.
            collectJob.join()
        } finally {
            cleanup(fixturePath)
            emissions.close()
        }
    }

    // ---- helpers -----------------------------------------------------------------------

    /** Writes [SampleVideoFixture.bytes] into NSCachesDirectory and returns the absolute path. */
    private fun stageFixtureOnDisk(): String {
        val cacheDir = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: NSTemporaryDirectory()
        val path = "$cacheDir/ffmpegkit_test_${NSUUID.UUID().UUIDString}.mp4"
        writeBytesToPath(SampleVideoFixture.bytes, path)
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
        writeBytesToPath(bytes, path)
    }

    private fun writeBytesToPath(bytes: ByteArray, path: String) {
        memScoped {
            val buffer = allocArrayOf(bytes)
            val data = NSData.create(bytes = buffer, length = bytes.size.toULong())
            check(data.writeToFile(path, true)) { "could not write bytes to $path" }
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

    override fun executeFFmpegAsyncArgs(
        args: List<String>,
        onProgress: (timeMs: Long) -> Unit,
        onComplete: (FFmpegResult) -> Unit,
    ) {
        executeAsyncCalled = true
        // Same shape — never complete; simulate a hung ffmpeg session.
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
        // Legacy shell-style command path. Not used by the strip decoder anymore (it migrated
        // to executeFFmpegAsyncArgs to dodge shell quoting), so this branch is now defensive.
        // Parse the output pattern by finding the quoted segment ending in `f_%04d.jpg`.
        val outPattern = command.split('"').firstOrNull { it.endsWith("f_%04d.jpg") }
            ?: error("could not find output pattern in: $command")
        outDirReady.complete(outPattern.substringBeforeLast("/f_%04d.jpg"))
        pendingCompletion = onComplete
    }

    override fun executeFFmpegAsyncArgs(
        args: List<String>,
        onProgress: (timeMs: Long) -> Unit,
        onComplete: (FFmpegResult) -> Unit,
    ) {
        // The output pattern is whichever arg ends in `f_%04d.jpg` — no shell parsing.
        val outPattern = args.firstOrNull { it.endsWith("f_%04d.jpg") }
            ?: error("could not find output pattern in args: $args")
        outDirReady.complete(outPattern.substringBeforeLast("/f_%04d.jpg"))
        pendingCompletion = onComplete
    }

    override fun cancelAllFFmpegSessions() {
        pendingCompletion?.invoke(FFmpegResult(isSuccess = false, failStackTrace = "cancelled"))
        pendingCompletion = null
    }

    override fun getMediaInformation(filePath: String): MediaInfo? = null

    override fun getFfmpegVersionBanner(): String? = null
}
