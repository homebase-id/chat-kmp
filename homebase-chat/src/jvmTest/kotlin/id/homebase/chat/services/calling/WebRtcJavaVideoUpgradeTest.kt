package id.homebase.chat.services.calling

import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Real webrtc-java engines on UDP loopback, with synthetic cameras: a voice call turns video on from either side. */
class WebRtcJavaVideoUpgradeTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"

    private class Cameras : AutoCloseable {
        val opened = CopyOnWriteArrayList<CustomVideoSource>()
        @Volatile private var running = true
        private val pump = Thread({
            while (running) {
                for (source in opened) {
                    val frame = VideoFrame(NativeI420Buffer.allocate(320, 240), System.nanoTime())
                    runCatching { source.pushFrame(frame) }
                    frame.release()
                }
                Thread.sleep(33)
            }
        }, "test-cameras").apply { isDaemon = true; start() }

        fun factory() = CallEngineFactory { stun ->
            WebRtcJavaCallEngine(stun, HeadlessAudioDeviceModule(), cameraSource = { CustomVideoSource().also { opened += it } })
        }

        override fun close() {
            running = false
            pump.join(1_000)
        }
    }

    private suspend fun CallController.receivesFrames() = withTimeout(15_000) {
        state.first { it.media.remoteVideo }
        val remote = videos.value.remote as JvmVideoHandle
        remote.frame.first { it != null }
    }

    @Test
    fun eitherSideOfAVoiceCallTurnsItsCameraOnWithoutRenegotiating() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cameras = Cameras()
        try {
            val net = LoopbackSignaling()
            val caller = CallController(frodo, net.endpoint(frodo), cameras.factory(), scope)
            val callee = CallController(sam, net.endpoint(sam), cameras.factory(), scope)

            caller.startCall(sam, video = false)
            withTimeout(15_000) { callee.state.first { it.phase == CallPhase.Incoming && it.pendingOfferSdp != null } }
            callee.accept()
            withTimeout(30_000) {
                caller.state.first { it.phase == CallPhase.Connected }
                callee.state.first { it.phase == CallPhase.Connected }
            }
            val offer = net.sent.map { it.signal }.filterIsInstance<CallSignal.Offer>().first().sdp
            val answer = net.sent.map { it.signal }.filterIsInstance<CallSignal.Answer>().single().sdp
            assertEquals("sendrecv", videoDirection(offer))
            assertEquals("sendrecv", videoDirection(answer))
            assertTrue(cameras.opened.isEmpty(), "a voice call opens no camera")
            assertFalse(caller.state.value.media.remoteVideo)
            assertFalse(callee.state.value.media.remoteVideo)
            val signalsBefore = net.sent.size

            caller.setVideo(true)
            callee.receivesFrames()
            assertTrue(caller.state.value.media.localVideo)
            assertEquals(1, cameras.opened.size)

            callee.setVideo(true)
            caller.receivesFrames()
            assertEquals(2, cameras.opened.size)

            assertNull(
                net.sent.drop(signalsBefore).map { it.signal }.firstOrNull { it is CallSignal.Offer || it is CallSignal.Restart || it is CallSignal.RestartAnswer },
                "turning a camera on never renegotiates",
            )
            assertEquals(CallPhase.Connected, caller.state.value.phase)

            caller.setVideo(false)
            withTimeout(5_000) { callee.state.first { !it.media.remoteVideo } }
            assertTrue(caller.state.value.media.remoteVideo, "the other camera is untouched")

            caller.hangup()
            withTimeout(10_000) { callee.state.first { it.phase is CallPhase.Ended } }
            caller.close()
            callee.close()
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            cameras.close()
        }
    }

    private fun videoDirection(sdp: String): String? =
        sdp.substringAfter("m=video", "").lineSequence().map { it.trim() }
            .takeWhile { !it.startsWith("m=") }
            .firstOrNull { it in setOf("a=sendrecv", "a=sendonly", "a=recvonly", "a=inactive") }
            ?.removePrefix("a=")
}
