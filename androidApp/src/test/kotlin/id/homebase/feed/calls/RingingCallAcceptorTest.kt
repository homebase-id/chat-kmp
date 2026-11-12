package id.homebase.feed.calls

import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.CallMedia
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallSession
import id.homebase.chat.services.calling.CallState
import id.homebase.chat.services.calling.CallVideos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class RingingCallAcceptorTest {
    private class FakeSession(initial: CallState = CallState()) : CallSession {
        val calls = mutableListOf<String>()
        override val state = MutableStateFlow(initial)
        override val transitions: Flow<CallState> get() = state
        override val videos: StateFlow<CallVideos> = MutableStateFlow(CallVideos())
        override fun startCall(peer: String, video: Boolean) { calls += "start" }
        override fun accept() { calls += "accept" }
        override fun decline() { calls += "decline" }
        override fun hangup() { calls += "hangup" }
        override fun setMuted(muted: Boolean) { calls += "mute $muted" }
        override fun setVideo(on: Boolean) { calls += "video $on" }
        override fun switchCamera() { calls += "switch" }
        override fun setAudioRoute(route: AudioRoute) { calls += "route" }
    }

    private fun ringing(callId: String, video: Boolean = false) =
        CallState(phase = CallPhase.Incoming, callId = callId, media = CallMedia(localVideo = video))

    @Test
    fun acceptsOnceTheRingForThatCallArrives() = runTest {
        val session = FakeSession()
        val acceptor = RingingCallAcceptor(session, backgroundScope, cameraGranted = { true })

        acceptor.accept("c1")
        runCurrent()
        assertEquals(emptyList(), session.calls)

        session.state.value = ringing("other")
        runCurrent()
        assertEquals(emptyList(), session.calls)

        session.state.value = ringing("c1")
        runCurrent()
        assertEquals(listOf("accept"), session.calls)
    }

    @Test
    fun givesUpWhenTheRingNeverArrives() = runTest {
        val session = FakeSession()
        RingingCallAcceptor(session, backgroundScope, cameraGranted = { true }, ringWaitMs = 10_000).accept("c1")
        runCurrent()

        advanceTimeBy(10_001)
        session.state.value = ringing("c1")
        runCurrent()

        assertEquals(emptyList(), session.calls)
    }

    @Test
    fun aVideoCallIsTakenAsVoiceOnlyWhenTheCameraIsDenied() = runTest {
        val denied = FakeSession(ringing("c1", video = true))
        RingingCallAcceptor(denied, backgroundScope, cameraGranted = { false }).accept("c1")
        runCurrent()
        assertEquals(listOf("video false", "accept"), denied.calls)

        val granted = FakeSession(ringing("c1", video = true))
        RingingCallAcceptor(granted, backgroundScope, cameraGranted = { true }).accept("c1")
        runCurrent()
        assertEquals(listOf("accept"), granted.calls)
    }
}
