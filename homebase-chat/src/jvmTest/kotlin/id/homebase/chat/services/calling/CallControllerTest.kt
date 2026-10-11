package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CallControllerTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"

    private class Side(
        val id: String,
        scope: TestScope,
        net: LoopbackSignaling,
        script: FakeCallScript,
    ) {
        val engines = mutableListOf<FakeCallEngine>()
        val controller = CallController(
            selfOdinId = id,
            signaling = net.endpoint(id),
            engineFactory = CallEngineFactory {
                FakeCallEngine(scope.backgroundScope, script, peerLabel = "peer", selfLabel = id).also { engines += it }
            },
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
        )
        val phase get() = controller.state.value.phase
        val state get() = controller.state.value
    }

    private fun TestScope.pair(
        script: FakeCallScript = FakeCallScript(),
        scriptB: FakeCallScript = script,
    ): Triple<LoopbackSignaling, Side, Side> {
        val net = LoopbackSignaling(now = { testScheduler.currentTime })
        return Triple(net, Side(frodo, this, net, script), Side(sam, this, net, scriptB))
    }

    @Test
    fun fullLoopbackCallConnectsThenHangsUpOnBoth() = runTest {
        val (_, a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        assertEquals(CallPhase.Outgoing, a.phase)
        assertEquals(CallPhase.Incoming, b.phase)
        assertTrue(a.state.peerRinging)
        assertEquals(frodo, b.state.peer)

        b.controller.accept()
        runCurrent()
        assertEquals(CallPhase.Connecting, a.phase)
        assertEquals(CallPhase.Connecting, b.phase)

        advanceTimeBy(1_001)
        assertEquals(CallPhase.Connected, a.phase)
        assertEquals(CallPhase.Connected, b.phase)
        assertNotNull(a.state.connectedAtMs)
        assertTrue(a.engines.single().addedIce.isEmpty() || a.engines.single().addedIce.all { it.startsWith("fake") })

        a.controller.hangup()
        runCurrent()
        assertEquals(CallPhase.Ended(CallEndReason.Hangup(remote = false)), a.phase)
        assertEquals(CallPhase.Ended(CallEndReason.Hangup(remote = true)), b.phase)
        assertTrue(a.engines.single().closed)
        assertTrue(b.engines.single().closed)
    }

    @Test
    fun localIceReachesPeerAfterRemoteDescription() = runTest {
        val (_, a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        runCurrent()
        assertEquals(listOf("fake-candidate-local"), b.engines.single().addedIce)
    }

    @Test
    fun ringTimeoutMissesOnBothSidesAfter45Seconds() = runTest {
        val (net, a, b) = pair()
        a.controller.startCall(sam, video = false)
        advanceTimeBy(RING_TIMEOUT_MS - 1)
        assertEquals(CallPhase.Incoming, b.phase)
        assertEquals(CallPhase.Outgoing, a.phase)
        advanceTimeBy(2)
        assertEquals(CallPhase.Ended(CallEndReason.Missed), a.phase)
        assertEquals(CallPhase.Ended(CallEndReason.Missed), b.phase)
        assertTrue(net.sent.any { it.signal is CallSignal.Hangup && it.from == frodo })
    }

    @Test
    fun callerHangsUpDuringRing() = runTest {
        val (_, a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        a.controller.hangup()
        runCurrent()
        assertEquals(CallPhase.Ended(CallEndReason.Cancelled), a.phase)
        assertEquals(CallPhase.Ended(CallEndReason.Cancelled), b.phase)
    }

    @Test
    fun calleeDeclines() = runTest {
        val (_, a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.decline()
        runCurrent()
        assertEquals(CallPhase.Ended(CallEndReason.Declined), a.phase)
        assertEquals(CallPhase.Ended(CallEndReason.Declined), b.phase)
    }

    @Test
    fun answeredOnAnotherDeviceStopsTheRing() = runTest {
        val (net, a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        val callId = b.state.callId!!
        net.deliver(to = sam, from = sam, signal = CallSignal.Answer(callId, "x"))
        runCurrent()
        assertEquals(CallPhase.Ended(CallEndReason.AnsweredElsewhere), b.phase)
        assertTrue(b.engines.isEmpty())
    }

    @Test
    fun secondInboundCallWhileBusyIsRefusedAndFirstSurvives() = runTest {
        val (net, a, b) = pair()
        val gimli = "gimli.demo.rocks"
        val gimliSide = Side(gimli, this, net, FakeCallScript())
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        advanceTimeBy(1_001)
        gimliSide.controller.startCall(sam, video = false)
        runCurrent()
        assertEquals(CallPhase.Connected, b.phase)
        assertEquals(frodo, b.state.peer)
        assertEquals(CallPhase.Ended(CallEndReason.Busy), gimliSide.phase)
    }

    @Test
    fun glareLowerOdinIdKeepsOfferAndBothConnect() = runTest {
        val (_, a, b) = pair()
        a.controller.startCall(sam, video = false, callId = "call-a")
        b.controller.startCall(frodo, video = false, callId = "call-b")
        runCurrent()
        advanceTimeBy(1_001)
        assertTrue(frodo < sam)
        assertEquals(CallPhase.Connected, a.phase)
        assertEquals(CallPhase.Connected, b.phase)
        assertEquals("call-a", a.state.callId)
        assertEquals("call-a", b.state.callId)
        assertEquals(CallDirection.Outgoing, a.state.direction)
        assertEquals(CallDirection.Incoming, b.state.direction)
    }

    @Test
    fun reconnectThenRecover() = runTest {
        val (_, a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        advanceTimeBy(1_001)
        a.engines.single().simulateDrop()
        runCurrent()
        assertEquals(CallPhase.Reconnecting, a.phase)
        assertEquals(1, a.state.reconnectAttempt)
        advanceTimeBy(RECONNECT_TICK_MS + 1)
        assertEquals(2, a.state.reconnectAttempt)
        a.engines.single().simulateRecover()
        runCurrent()
        assertEquals(CallPhase.Connected, a.phase)
        assertEquals(0, a.state.reconnectAttempt)
        advanceTimeBy(RECONNECT_TICK_MS * 5)
        assertEquals(CallPhase.Connected, a.phase)
    }

    @Test
    fun reconnectThenFailEndsBothSides() = runTest {
        val (_, a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        advanceTimeBy(1_001)
        a.engines.single().simulateDrop()
        runCurrent()
        advanceTimeBy(RECONNECT_TICK_MS * MAX_RECONNECT_ATTEMPTS + 1)
        assertEquals(CallPhase.Ended(CallEndReason.Failed(FailReason.NoRoute)), a.phase)
        assertEquals(CallPhase.Ended(CallEndReason.Hangup(remote = true)), b.phase)
    }

    @Test
    fun connectTimeoutFailsTheCall() = runTest {
        val (_, a, b) = pair(FakeCallScript(connectAfterMs = 10 * CONNECT_TIMEOUT_MS))
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        advanceTimeBy(CONNECT_TIMEOUT_MS + 1)
        assertEquals(CallPhase.Ended(CallEndReason.Failed(FailReason.NoRoute)), a.phase)
        assertEquals(CallPhase.Ended(CallEndReason.Failed(FailReason.NoRoute)), b.phase)
    }

    @Test
    fun permissionDeniedEndsWithPermissionFailure() = runTest {
        val (_, a, _) = pair()
        a.controller.startCall(sam, video = true)
        runCurrent()
        a.controller.onPermissionDenied()
        runCurrent()
        assertEquals(CallPhase.Ended(CallEndReason.Failed(FailReason.Permission)), a.phase)
    }

    @Test
    fun controlsAndRemoteVideoReachStateAndEngine() = runTest {
        val (_, a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        advanceTimeBy(1_001)
        a.controller.setMuted(true)
        a.controller.setVideo(true)
        a.controller.switchCamera()
        a.controller.setAudioRoute(AudioRoute.Speaker)
        runCurrent()
        val m = a.state.media
        assertTrue(m.muted && m.localVideo)
        assertEquals(CameraFacing.Back, m.camera)
        assertEquals(AudioRoute.Speaker, m.route)
        val e = a.engines.single()
        assertTrue(e.muted && !e.frontCamera && e.route == AudioRoute.Speaker && e.localVideo.value != null)
        a.engines.single().simulateRemoteVideo(true)
        runCurrent()
        assertTrue(a.state.media.remoteVideo)
    }

    @Test
    fun staleOrReplayedOfferDoesNotRing() = runTest {
        val (net, _, b) = pair()
        advanceTimeBy(RING_TIMEOUT_MS + 5_000)
        net.deliver(sam, frodo, CallSignal.Offer("old", "sdp", false), receivedAtMs = 0)
        runCurrent()
        assertEquals(CallPhase.Idle, b.phase)
    }

    @Test
    fun replayedOfferOfAnEndedCallDoesNotRingAgain() = runTest {
        val (net, a, b) = pair()
        a.controller.startCall(sam, video = false, callId = "c1")
        runCurrent()
        a.controller.hangup()
        runCurrent()
        net.deliver(sam, frodo, CallSignal.Offer("c1", "sdp", false))
        runCurrent()
        assertIs<CallPhase.Ended>(b.phase)
    }

    @Test
    fun signalCodecRoundTripsEveryVariantAndRejectsGarbage() {
        val all = listOf(
            CallSignal.Offer("c", "sdp", true),
            CallSignal.Ring("c"),
            CallSignal.Answer("c", "sdp"),
            CallSignal.Ice("c", "cand", "0", 1),
            CallSignal.Hangup("c", HangupReason.Busy),
        )
        all.forEach { assertEquals(it, CallSignalCodec.decode(CallSignalCodec.encode(it))) }
        assertEquals(null, CallSignalCodec.decode("not-base64!!"))
    }
}
