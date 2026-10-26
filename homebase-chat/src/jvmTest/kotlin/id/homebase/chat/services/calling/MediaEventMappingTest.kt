package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Stands in for a native peer connection: tests push its callbacks, the engine maps them exactly as the real ones do. */
private class FakePeerConnection {
    var onLinkState: (PeerLinkState) -> Unit = {}
    var onIce: (String) -> Unit = {}
    var onRemoteVideo: () -> Unit = {}
    val addedIce = mutableListOf<String>()
    var closed = false
}

private class PeerBackedEngine(private val peer: FakePeerConnection, private val facing: CameraFacing = CameraFacing.Front) : CallEngine {
    private val flow = MutableSharedFlow<MediaEvent>(extraBufferCapacity = 64)
    private val mapper = PeerEventMapper { flow.tryEmit(it) }
    override val events = flow
    override val localVideo: StateFlow<VideoHandle?> = MutableStateFlow(null)
    override val remoteVideo: StateFlow<VideoHandle?> = MutableStateFlow(null)

    init {
        peer.onLinkState = mapper::linkState
        peer.onIce = { mapper.localIce(it, "0", 0) }
        peer.onRemoteVideo = { mapper.remoteVideo(true) }
    }

    override suspend fun createOffer(video: Boolean) = FakeCallEngine.FAKE_OFFER_SDP
    override suspend fun answer(offerSdp: String, video: Boolean) = "answer"
    override suspend fun applyRemote(answerSdp: String) = Unit
    override suspend fun restartIce() = "restart"
    override suspend fun answerRestart(offerSdp: String) = "restart-answer"
    override suspend fun addIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) { peer.addedIce += candidate }
    override fun setMuted(muted: Boolean) = Unit
    override fun setVideo(on: Boolean) = Unit
    override fun switchCamera() = Unit
    override fun setAudioRoute(route: AudioRoute) = Unit
    override fun close() { peer.closed = true }
}

class MediaEventMappingTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"

    private class Side(id: String, scope: TestScope, net: LoopbackSignaling) {
        val peer = FakePeerConnection()
        val controller = CallController(
            selfOdinId = id,
            signaling = net.endpoint(id),
            engineFactory = CallEngineFactory { _ -> PeerBackedEngine(peer) },
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
        )
        val phase get() = controller.state.value.phase
    }

    private suspend fun TestScope.connected(): Pair<Side, Side> {
        val net = LoopbackSignaling(now = { testScheduler.currentTime })
        val a = Side(frodo, this, net)
        val b = Side(sam, this, net)
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        runCurrent()
        a.peer.onLinkState(PeerLinkState.Connecting)
        a.peer.onLinkState(PeerLinkState.Connected)
        b.peer.onLinkState(PeerLinkState.Connected)
        runCurrent()
        return a to b
    }

    @Test
    fun nativeConnectedDrivesBothControllersToConnected() = runTest {
        val (a, b) = connected()
        assertEquals(CallPhase.Connected, a.phase)
        assertEquals(CallPhase.Connected, b.phase)
    }

    @Test
    fun disconnectAfterConnectedMapsToReconnectingNotFailure() = runTest {
        val (a, _) = connected()
        a.peer.onLinkState(PeerLinkState.Disconnected)
        runCurrent()
        assertEquals(CallPhase.Reconnecting, a.phase)

        a.peer.onLinkState(PeerLinkState.Connected)
        runCurrent()
        assertEquals(CallPhase.Connected, a.phase)
    }

    @Test
    fun failedAfterConnectedIsAReconnectNotAnEndedCall() = runTest {
        val (a, _) = connected()
        a.peer.onLinkState(PeerLinkState.Failed)
        runCurrent()
        assertEquals(CallPhase.Reconnecting, a.phase)
    }

    @Test
    fun failedBeforeEverConnectedEndsWithNoRoute() = runTest {
        val net = LoopbackSignaling(now = { testScheduler.currentTime })
        val a = Side(frodo, this, net)
        val b = Side(sam, this, net)
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        runCurrent()
        a.peer.onLinkState(PeerLinkState.Failed)
        runCurrent()
        assertEquals(CallPhase.Ended(CallEndReason.Failed(FailReason.NoRoute)), a.phase)
        assertTrue(a.peer.closed)
    }

    @Test
    fun localCandidatesReachThePeerEngineThroughSignalling() = runTest {
        val (a, b) = connected()
        val candidate = "candidate:1 1 udp 2122260223 10.0.0.2 50000 typ host"
        a.peer.onIce(candidate)
        runCurrent()
        assertTrue(candidate in b.peer.addedIce)
    }

    @Test
    fun hangupClosesTheNativeConnectionOnBothSides() = runTest {
        val (a, b) = connected()
        a.controller.hangup()
        runCurrent()
        assertTrue(a.peer.closed)
        assertTrue(b.peer.closed)
    }

    @Test
    fun onlyTheFrontCameraPreviewIsMirrored() {
        assertTrue(CameraFacing.Front.mirrorsLocalPreview)
        assertFalse(CameraFacing.Back.mirrorsLocalPreview)
        assertFalse(JvmVideoHandle().mirrored)
    }
}
