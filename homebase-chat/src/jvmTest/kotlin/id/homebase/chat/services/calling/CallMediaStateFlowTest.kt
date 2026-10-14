package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Two controllers whose fake engines share the in-call media-state channel, as the real engines' data channel does. */
@OptIn(ExperimentalCoroutinesApi::class)
class CallMediaStateFlowTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"

    private class Side(id: String, scope: TestScope, net: LoopbackSignaling) {
        val engines = mutableListOf<FakeCallEngine>()
        val controller = CallController(
            selfOdinId = id,
            signaling = net.endpoint(id),
            engineFactory = CallEngineFactory { _ -> FakeCallEngine(scope.backgroundScope).also { engines += it } },
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
        )
        val media get() = controller.state.value.media
    }

    private fun TestScope.videoCall(): Pair<Side, Side> = call(video = true)

    private fun TestScope.call(video: Boolean): Pair<Side, Side> {
        val net = LoopbackSignaling(now = { testScheduler.currentTime })
        val a = Side(frodo, this, net)
        val b = Side(sam, this, net)
        a.controller.startCall(sam, video = video)
        runCurrent()
        b.controller.accept()
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(CallPhase.Connected, a.controller.state.value.phase)
        FakeCallEngine.connectMediaState(a.engines.single(), b.engines.single())
        runCurrent()
        return a to b
    }

    @Test
    fun turningTheCameraOffShowsTheAvatarOnThePeerAndBackOnResumesVideo() = runTest {
        val (a, b) = videoCall()
        assertTrue(b.media.remoteVideo)
        assertTrue(a.media.remoteVideo)

        a.controller.setVideo(false)
        runCurrent()
        assertFalse(b.media.remoteVideo)
        assertTrue(a.media.remoteVideo, "only the side that turned its camera off goes dark")

        a.controller.setVideo(true)
        runCurrent()
        assertTrue(b.media.remoteVideo)

        b.controller.setVideo(false)
        runCurrent()
        assertFalse(a.media.remoteVideo)
    }

    @Test
    fun aCameraInterruptionShowsTheAvatarUntilTheCameraIsBack() = runTest {
        val (a, b) = videoCall()
        a.engines.single().simulateCameraInterrupted(true)
        runCurrent()
        assertFalse(b.media.remoteVideo)
        assertTrue(a.media.localVideo, "the user's own camera choice is untouched")

        a.engines.single().simulateCameraInterrupted(false)
        runCurrent()
        assertTrue(b.media.remoteVideo)
    }

    @Test
    fun eitherSideOfAVoiceCallTurnsItsCameraOnAndThePeerSeesIt() = runTest {
        val (a, b) = call(video = false)
        assertFalse(a.media.remoteVideo)
        assertFalse(b.media.remoteVideo)

        b.controller.setVideo(true)
        runCurrent()
        assertTrue(b.media.localVideo)
        assertEquals(AudioRoute.Speaker, b.media.route)
        assertTrue(a.media.remoteVideo)
        assertFalse(a.media.localVideo, "the peer's camera stays off until it turns it on")

        a.controller.setVideo(true)
        runCurrent()
        assertTrue(b.media.remoteVideo)
        assertEquals(1, a.engines.size, "the camera joins the same engine")

        b.controller.setVideo(false)
        runCurrent()
        assertFalse(a.media.remoteVideo)
        assertEquals(CallPhase.Connected, a.controller.state.value.phase)
    }

    @Test
    fun muteReachesThePeer() = runTest {
        val (a, b) = videoCall()
        a.controller.setMuted(true)
        runCurrent()
        assertTrue(b.media.remoteMuted)
        a.controller.setMuted(false)
        runCurrent()
        assertFalse(b.media.remoteMuted)
    }
}
