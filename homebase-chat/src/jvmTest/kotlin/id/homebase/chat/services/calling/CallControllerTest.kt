package id.homebase.chat.services.calling

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        wrap: (FakeCallEngine) -> CallEngine = { it },
    ) {
        val engines = mutableListOf<FakeCallEngine>()
        var offline = false
        var sendHangs = false
        private val link = net.endpoint(id)
        val controller = CallController(
            selfOdinId = id,
            signaling = object : CallSignaling by link {
                override suspend fun send(outbound: OutboundSignal) {
                    if (offline) throw IllegalStateException("Unable to resolve host")
                    if (sendHangs) awaitCancellation()
                    link.send(outbound)
                }
            },
            engineFactory = CallEngineFactory { _ ->
                wrap(FakeCallEngine(scope.backgroundScope, script, peerLabel = "peer", selfLabel = id).also { engines += it })
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
        wrapA: (FakeCallEngine) -> CallEngine = { it },
    ): Triple<LoopbackSignaling, Side, Side> {
        val net = LoopbackSignaling(now = { testScheduler.currentTime })
        return Triple(net, Side(frodo, this, net, script, wrapA), Side(sam, this, net, scriptB))
    }

    private val applyRemoteHangs: (FakeCallEngine) -> CallEngine = { fake ->
        object : CallEngine by fake {
            override suspend fun applyRemote(answerSdp: String) = awaitCancellation()
        }
    }

    @Test
    fun callerRelaysAnsweredElsewhereToTheCalleeAndBothStillConnect() = runTest {
        val (net, a, b) = pair()
        a.controller.startCall(sam, video = true)
        runCurrent()
        b.controller.accept()
        runCurrent()
        advanceTimeBy(1_001)

        assertTrue(net.sent.any { it.from == frodo && it.to == sam && it.signal == CallSignal.Hangup(a.state.callId!!, HangupReason.AnsweredElsewhere) })
        assertEquals(CallPhase.Connected, a.phase)
        assertEquals(CallPhase.Connected, b.phase)
    }

    @Test
    fun localHangupEndsTheCallWhileApplyingTheAnswerHangs() = runTest {
        val (net, a, b) = pair(wrapA = applyRemoteHangs)
        a.controller.startCall(sam, video = true)
        runCurrent()
        b.controller.accept()
        advanceTimeBy(1_001)
        assertEquals(CallPhase.Connecting, a.phase)

        a.controller.hangup()
        runCurrent()

        assertEquals(CallPhase.Ended(CallEndReason.Hangup(remote = false)), a.phase)
        assertTrue(a.engines.single().closed)
        assertTrue(net.sent.any { it.from == frodo && it.signal == CallSignal.Hangup(a.state.callId!!, HangupReason.Normal) })
        assertEquals(CallPhase.Ended(CallEndReason.Hangup(remote = true)), b.phase)
    }

    @Test
    fun remoteHangupEndsTheCallWhileApplyingTheAnswerHangs() = runTest {
        val (_, a, b) = pair(wrapA = applyRemoteHangs)
        a.controller.startCall(sam, video = true)
        runCurrent()
        b.controller.accept()
        advanceTimeBy(1_001)
        assertEquals(CallPhase.Connecting, a.phase)

        b.controller.hangup()
        runCurrent()

        assertEquals(CallPhase.Ended(CallEndReason.Hangup(remote = true)), a.phase)
        assertTrue(a.engines.single().closed)
    }

    @Test
    fun aStaleHangupForAnotherCallDoesNotInterruptTheLiveOne() = runTest {
        val (net, a, b) = pair(wrapA = applyRemoteHangs)
        a.controller.startCall(sam, video = true)
        runCurrent()
        b.controller.accept()
        advanceTimeBy(1_001)

        net.endpoint(sam).send(CallChannel.outbound(frodo, CallSignal.Hangup("another-call", HangupReason.Normal)))
        runCurrent()

        assertEquals(CallPhase.Connecting, a.phase)
        assertTrue(!a.engines.single().closed)
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
        assertEquals(emptyList(), a.engines.single().addedIce)
        assertEquals(listOf(FakeCallEngine.FAKE_CANDIDATE), b.engines.single().addedIce)

        a.controller.hangup()
        runCurrent()
        assertEquals(CallPhase.Ended(CallEndReason.Hangup(remote = false)), a.phase)
        assertEquals(CallPhase.Ended(CallEndReason.Hangup(remote = true)), b.phase)
        assertTrue(a.engines.single().closed)
        assertTrue(b.engines.single().closed)
    }

    @Test
    fun quittingMidCallHangsUpThePeerAndReleasesTheEngine() = runTest {
        val (_, a, b) = pair()
        a.controller.startCall(sam, video = true)
        runCurrent()
        b.controller.accept()
        runCurrent()
        advanceTimeBy(1_001)
        assertEquals(CallPhase.Connected, a.phase)

        a.controller.endForQuit()
        runCurrent()

        assertTrue(a.engines.single().closed)
        assertEquals(CallPhase.Ended(CallEndReason.Hangup(remote = true)), b.phase)
    }

    @Test
    fun quittingWhileTheHangupCannotBeSentStillReleasesTheEngine() = runTest {
        val (_, a, b) = pair()
        a.controller.startCall(sam, video = true)
        runCurrent()
        b.controller.accept()
        runCurrent()
        advanceTimeBy(1_001)
        a.sendHangs = true

        val quit = launch { a.controller.endForQuit(hangupBudgetMs = 2_000) }
        runCurrent()
        assertTrue(quit.isActive)
        advanceTimeBy(2_001)

        assertTrue(quit.isCompleted)
        assertTrue(a.engines.single().closed)
    }

    @Test
    fun videoOfferAcceptedWithoutCameraAnswersAsVoice() = runTest {
        val (_, a, b) = pair()
        a.controller.startCall(sam, video = true)
        runCurrent()
        assertTrue(b.state.media.localVideo)
        b.controller.setVideo(false)
        b.controller.accept()
        runCurrent()
        advanceTimeBy(1_001)
        assertEquals(CallPhase.Connected, b.phase)
        assertEquals(null, b.engines.single().localVideo.value)
        assertEquals(false, b.state.media.localVideo)
    }

    @Test
    fun videoOfferAcceptedWithCameraAnswersWithVideo() = runTest {
        val (_, a, b) = pair()
        a.controller.startCall(sam, video = true)
        runCurrent()
        b.controller.accept()
        runCurrent()
        assertNotNull(b.engines.single().localVideo.value)
    }

    @Test
    fun localIceReachesPeerAfterRemoteDescription() = runTest {
        val (_, a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        runCurrent()
        assertEquals(listOf(FakeCallEngine.FAKE_CANDIDATE), b.engines.single().addedIce)
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
        assertTrue(listOf(a, b).flatMap { it.engines }.all { it.closed })
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
        assertTrue(listOf(a, b).flatMap { it.engines }.all { it.closed })
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
        assertTrue(listOf(a, b).flatMap { it.engines }.all { it.closed })
    }

    @Test
    fun answerFromOwnIdentityIsIgnored() = runTest {
        val (net, a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        val callId = b.state.callId!!
        net.deliver(to = sam, from = sam, signal = CallSignal.Answer(callId, "x"))
        runCurrent()
        assertEquals(CallPhase.Incoming, b.phase)
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
        assertTrue(gimliSide.engines.all { it.closed })
    }

    @Test
    fun glareLowerOdinIdKeepsOfferAndBothConnect() = runTest {
        val (net, a, b) = pair()
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
        assertTrue(net.sent.any { it.from == sam && it.to == frodo && it.signal == CallSignal.Hangup("call-b", HangupReason.AnsweredElsewhere) })
        assertTrue(net.sent.none { it.signal is CallSignal.Hangup && !it.signal.reason.isElsewhere && it.push != null })
        assertEquals(1, net.sent.count { it.push?.kind == CallPushKind.Ring && it.from == frodo })
        assertEquals(1, net.sent.count { it.push?.kind == CallPushKind.Ring && it.from == sam })
        assertEquals(2, b.engines.size)
        assertTrue(b.engines.first().closed)
        assertTrue(!b.engines.last().closed)
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
        assertTrue(listOf(a, b).flatMap { it.engines }.all { it.closed })
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
        assertTrue(listOf(a, b).flatMap { it.engines }.all { it.closed })
    }

    @Test
    fun permissionDeniedEndsWithPermissionFailure() = runTest {
        val (_, a, _) = pair()
        a.controller.startCall(sam, video = true)
        runCurrent()
        a.controller.onPermissionDenied()
        runCurrent()
        assertEquals(CallPhase.Ended(CallEndReason.Failed(FailReason.Permission)), a.phase)
        assertTrue(a.engines.isNotEmpty() && a.engines.all { it.closed })
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
        net.deliver(sam, frodo, CallSignal.Offer("old", "sdp", false), receivedAtMs = 1)
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
            CallSignal.Ringing("c"),
            CallSignal.Answer("c", "sdp"),
            CallSignal.Ice("c", "cand", "0", 1),
            CallSignal.Hangup("c", HangupReason.Busy),
        )
        all.forEach { assertEquals(it, CallSignalCodec.decode(CallSignalCodec.encode(it))) }
        assertEquals(null, CallSignalCodec.decode("not-base64!!"))
    }

    @Test
    fun defaultClocksRingTheCallee() = runTest {
        val net = LoopbackSignaling()
        val scope = CoroutineScope(Job() + UnconfinedTestDispatcher(testScheduler))
        val callee = CallController(sam, net.endpoint(sam), CallEngineFactory { _ -> FakeCallEngine(scope) }, scope)
        val caller = CallController(frodo, net.endpoint(frodo), CallEngineFactory { _ -> FakeCallEngine(scope) }, scope)
        caller.startCall(sam, video = false)
        runCurrent()
        assertEquals(CallPhase.Incoming, callee.state.value.phase)
        scope.cancel()
    }

    @Test
    fun cancellingTheScopeDuringConnectedClosesTheEngine() = runTest {
        val net = LoopbackSignaling(now = { testScheduler.currentTime })
        val scopeA = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val engines = mutableListOf<FakeCallEngine>()
        val a = CallController(frodo, net.endpoint(frodo), CallEngineFactory { _ ->
            FakeCallEngine(scopeA).also { engines += it }
        }, scopeA, clock = { testScheduler.currentTime })
        val b = Side(sam, this, net, FakeCallScript())
        a.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        advanceTimeBy(1_001)
        assertEquals(CallPhase.Connected, a.state.value.phase)
        assertTrue(!engines.single().closed)
        scopeA.cancel()
        runCurrent()
        assertTrue(engines.single().closed)
    }

    @Test
    fun concurrentFirstUseOfOneInboxLosesNoSignal() = runTest {
        val users = 8
        val senders = 8
        withContext(Dispatchers.Default.limitedParallelism(2)) {
            repeat(300) { round ->
                val net = LoopbackSignaling(now = { 0L })
                val subscribed = CompletableDeferred<Unit>()
                val gate = CompletableDeferred<Unit>()
                val received = Channel<InboundSignal>(Channel.UNLIMITED)
                val spinning = AtomicInteger()
                val allSpinning = CompletableDeferred<Unit>()
                val collector = launch {
                    allSpinning.await()
                    (net.endpoint("target").incoming as SharedFlow<InboundSignal>).onSubscription { subscribed.complete(Unit) }
                        .collect { if (it.signal.callId.startsWith("c")) received.trySend(it) }
                }
                coroutineScope {
                    repeat(users) { i -> launch { gate.await(); repeat(users) { net.endpoint("user$it") } } }
                    repeat(senders) { i ->
                        launch {
                            gate.await()
                            if (spinning.incrementAndGet() == senders) allSpinning.complete(Unit)
                            while (!subscribed.isCompleted) {
                                net.deliver("target", "warm", CallSignal.Ringing("warm"), 0)
                                yield()
                            }
                            subscribed.await()
                            net.deliver("target", "p$i", CallSignal.Ringing("c$i"), 0)
                        }
                    }
                    gate.complete(Unit)
                }
                val ids = withTimeout(5_000) { List(senders) { received.receive().signal.callId } }
                assertEquals((0 until senders).map { "c$it" }.toSet(), ids.toSet(), "round $round")
                collector.cancel()

                repeat(users) { i ->
                    val got = Channel<InboundSignal>(Channel.UNLIMITED)
                    val job = launch(Dispatchers.Unconfined) {
                        net.endpoint("user$i").incoming.collect { got.trySend(it) }
                    }
                    net.deliver("user$i", "x", CallSignal.Ringing("u$i"), 0)
                    assertEquals("u$i", withTimeout(5_000) { got.receive() }.signal.callId, "round $round user$i")
                    job.cancel()
                }
            }
        }
    }

    @Test
    fun ringTimeoutMissesOnBothSidesWithNetworkLatency() = runTest {
        val net = LoopbackSignaling(latencyMs = 500, now = { testScheduler.currentTime })
        val a = Side(frodo, this, net, FakeCallScript())
        val b = Side(sam, this, net, FakeCallScript())
        a.controller.startCall(sam, video = false)
        advanceTimeBy(RING_TIMEOUT_MS + 5_000)
        assertEquals(CallPhase.Ended(CallEndReason.Missed), a.phase)
        assertEquals(CallPhase.Ended(CallEndReason.Missed), b.phase)
        assertTrue(net.sent.any { it.from == frodo && it.signal == CallSignal.Hangup(a.state.callId!!, HangupReason.Missed) })
    }

    private fun failingOfferEnds(error: Throwable, expected: FailReason) = runTest {
        val (_, a, _) = pair(FakeCallScript(failOnCreateOffer = error))
        a.controller.startCall(sam, video = false)
        runCurrent()
        assertEquals(CallPhase.Ended(CallEndReason.Failed(expected)), a.phase)
        assertTrue(a.engines.single().closed)
    }

    @Test
    fun engineExceptionEndsFailedWithItsReasonAndClosesEngine() =
        failingOfferEnds(CallEngineException(FailReason.Permission, "mic"), FailReason.Permission)

    @Test
    fun genericEngineExceptionEndsFailedOtherAndClosesEngine() =
        failingOfferEnds(IllegalStateException("boom"), FailReason.Other)

    @Test
    fun iceRestartRunsThroughTheEnginesOnReconnecting() = runTest {
        val (net, a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        advanceTimeBy(1_001)
        a.engines.single().simulateDrop()
        runCurrent()
        assertTrue(net.sent.any { it.from == frodo && it.signal is CallSignal.Restart })
        assertTrue(net.sent.any { it.from == sam && it.signal is CallSignal.RestartAnswer })
        assertEquals(1, a.engines.single().restarts)
        assertEquals(1, b.engines.single().restarts)
    }

    @Test
    fun callerOfflineWhileReconnectingRestartsIceOnceTheNetworkReturns() = runTest {
        val (net, a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        advanceTimeBy(1_001)
        a.offline = true
        a.engines.single().simulateDrop()
        b.engines.single().simulateDrop()
        runCurrent()
        assertEquals(CallPhase.Reconnecting, a.phase)
        assertEquals(CallPhase.Reconnecting, b.phase)
        assertTrue(net.sent.none { it.from == frodo && it.signal is CallSignal.Restart })

        a.offline = false
        advanceTimeBy(RECONNECT_TICK_MS + 1)
        assertTrue(net.sent.any { it.from == frodo && it.signal is CallSignal.Restart })
        assertTrue(net.sent.any { it.from == sam && it.signal is CallSignal.RestartAnswer })
        a.engines.single().simulateRecover()
        b.engines.single().simulateRecover()
        runCurrent()
        assertEquals(CallPhase.Connected, a.phase)
        assertEquals(CallPhase.Connected, b.phase)
    }

    // Device timeline: ICE flagged the drop ~6 s after wifi went off, and the phone had no route for ~17 s after that.
    @Test
    fun bothSidesSurviveAnOutageThatOutlastsFifteenSecondsOfIceDisconnected() = runTest {
        val (net, a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        advanceTimeBy(1_001)
        a.offline = true
        a.engines.single().simulateDrop()
        b.engines.single().simulateDrop()
        runCurrent()

        advanceTimeBy(17_000)
        assertEquals(CallPhase.Reconnecting, a.phase)
        assertEquals(CallPhase.Reconnecting, b.phase)
        a.offline = false
        advanceTimeBy(RECONNECT_TICK_MS + 1)
        assertTrue(net.sent.any { it.from == frodo && it.signal is CallSignal.Restart })
        assertTrue(net.sent.any { it.from == sam && it.signal is CallSignal.RestartAnswer })
        a.engines.single().simulateRecover()
        b.engines.single().simulateRecover()
        runCurrent()
        assertEquals(CallPhase.Connected, a.phase)
        assertEquals(CallPhase.Connected, b.phase)
    }

    @Test
    fun callerLinkUpBeforeItsUndeliveredRestartKeepsRestartingUntilAnswered() = runTest {
        val (net, a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        advanceTimeBy(1_001)
        a.offline = true
        a.engines.single().simulateDrop()
        b.engines.single().simulateDrop()
        runCurrent()
        a.engines.single().simulateRecover()
        runCurrent()
        assertEquals(CallPhase.Reconnecting, a.phase)

        a.offline = false
        advanceTimeBy(RECONNECT_TICK_MS + 1)
        assertTrue(net.sent.any { it.from == frodo && it.signal is CallSignal.Restart })
        assertTrue(net.sent.any { it.from == sam && it.signal is CallSignal.RestartAnswer })
        assertEquals(1, a.engines.single().restarts)
        assertEquals(CallPhase.Connected, a.phase)
        b.engines.single().simulateRecover()
        runCurrent()
        assertEquals(CallPhase.Connected, b.phase)
        advanceTimeBy(RECONNECT_TICK_MS * MAX_RECONNECT_ATTEMPTS + 1)
        assertEquals(CallPhase.Connected, a.phase)
        assertEquals(CallPhase.Connected, b.phase)
    }

    @Test
    fun pushRingAndSocketOfferCollapseIntoOneIncomingCall() = runTest {
        val (net, a, b) = pair()
        b.controller.pushRing("pushed", frodo)
        runCurrent()
        assertEquals(CallPhase.Incoming, b.phase)
        a.controller.startCall(sam, video = false, callId = "pushed")
        runCurrent()
        assertEquals("pushed", b.state.callId)
        assertEquals(CallPhase.Incoming, b.phase)
        b.controller.accept()
        advanceTimeBy(1_001)
        assertEquals(CallPhase.Connected, b.phase)
        assertEquals(CallPhase.Connected, a.phase)
        assertEquals(1, b.engines.size)
        assertTrue(net.sent.any { it.signal is CallSignal.Offer && it.push?.kind == CallPushKind.Ring && it.push.tagId == "pushed" })
    }

    @Test
    fun closeReleasesTheEngineOnTheInboxLoop() = runTest {
        val (_, a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        advanceTimeBy(1_001)
        a.controller.close()
        runCurrent()
        assertTrue(a.engines.all { it.closed })
    }

    @Test
    fun aRingingSignalThatFailsToSendLeavesTheIncomingCallRinging() = runTest {
        val (_, a, b) = pair()
        b.offline = true
        a.controller.startCall(sam, video = false)
        runCurrent()
        assertEquals(CallPhase.Incoming, b.phase)

        b.offline = false
        b.controller.accept()
        runCurrent()
        advanceTimeBy(1_001)
        assertEquals(CallPhase.Connected, b.phase)
        assertEquals(CallPhase.Connected, a.phase)
    }

    @Test
    fun aRemoteCandidateTheEngineRejectsIsDroppedWithoutEndingTheCall() = runTest {
        val rejectsIce: (FakeCallEngine) -> CallEngine = { fake ->
            object : CallEngine by fake {
                override suspend fun addIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) =
                    throw IllegalStateException("bad candidate")
            }
        }
        val (_, a, b) = pair(wrapA = rejectsIce)
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        runCurrent()
        advanceTimeBy(1_001)
        assertEquals(CallPhase.Connected, a.phase)
    }

    @Test
    fun aRingPushedAfterTheControllerClosedIsCancelledNotLeftPending() = runTest {
        val (_, a, _) = pair()
        a.controller.close()
        runCurrent()
        val ring = a.controller.pushRing("c1", sam)
        runCurrent()
        assertTrue(ring.isCancelled)
    }
}
