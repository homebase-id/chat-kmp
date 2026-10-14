package id.homebase.chat.services.calling

import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Real webrtc-java engines on UDP loopback: a camera turned off or a mic muted on one side reaches the other over the data channel. */
class WebRtcJavaMediaStateTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"

    private fun headless() = CallEngineFactory { stun -> WebRtcJavaCallEngine(stun, HeadlessAudioDeviceModule()) }

    @Test
    fun cameraOffAndMuteOnOneSideReachTheOther() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val net = LoopbackSignaling()
            val caller = CallController(frodo, net.endpoint(frodo), headless(), scope)
            val callee = CallController(sam, net.endpoint(sam), headless(), scope)
            caller.startCall(sam, video = true)
            withTimeout(15_000) { callee.state.first { it.phase == CallPhase.Incoming && it.pendingOfferSdp != null } }
            callee.accept()
            withTimeout(30_000) {
                caller.state.first { it.phase == CallPhase.Connected }
                callee.state.first { it.phase == CallPhase.Connected }
            }
            caller.setMuted(true)
            withTimeout(5_000) { callee.state.first { it.media.remoteMuted } }

            // Without a usable camera on this host there is no video track to turn off.
            if (caller.videos.value.local == null) return@runBlocking
            withTimeout(5_000) { callee.state.first { it.media.remoteVideo } }

            caller.setVideo(false)
            withTimeout(5_000) { callee.state.first { !it.media.remoteVideo } }

            caller.setVideo(true)
            withTimeout(5_000) { callee.state.first { it.media.remoteVideo } }
            assertEquals(CallPhase.Connected, callee.state.value.phase)

            caller.hangup()
            caller.close()
            callee.close()
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
