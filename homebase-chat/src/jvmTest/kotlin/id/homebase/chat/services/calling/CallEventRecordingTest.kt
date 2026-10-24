package id.homebase.chat.services.calling

import id.homebase.chat.calling.CallEventDescriptor
import id.homebase.chat.calling.CallOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class CallEventRecordingTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"

    private class Side(id: String, scope: TestScope, net: LoopbackSignaling, script: FakeCallScript) {
        val recorded = mutableListOf<Pair<String, CallEventDescriptor>>()
        val controller = CallController(
            selfOdinId = id,
            signaling = net.endpoint(id),
            engineFactory = CallEngineFactory { _ -> FakeCallEngine(scope.backgroundScope, script, peerLabel = "peer", selfLabel = id) },
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
        )

        init {
            scope.backgroundScope.launch {
                recordCallEvents(controller.transitions) { peer, event -> recorded += peer to event }
            }
        }
    }

    private fun TestScope.pair(script: FakeCallScript = FakeCallScript()): Pair<Side, Side> {
        val net = LoopbackSignaling(now = { testScheduler.currentTime })
        return Side(frodo, this, net, script) to Side(sam, this, net, script)
    }

    private fun Side.single() = recorded.single().second

    @Test
    fun completedVideoCallIsLoggedOnceByTheCallerWithDurationAndPlacedMedia() = runTest {
        val (a, b) = pair()
        a.controller.startCall(sam, video = true)
        runCurrent()
        b.controller.accept()
        runCurrent()
        advanceTimeBy(1_001)
        a.controller.setVideo(false)
        b.controller.setVideo(false)
        runCurrent()
        advanceTimeBy(65_000)
        b.controller.hangup()
        runCurrent()

        assertEquals(sam, a.recorded.single().first)
        with(a.single()) {
            assertEquals(CallOutcome.Completed, outcome)
            assertTrue(video)
            assertTrue(durationSeconds in 65..66, "duration $durationSeconds")
        }
        assertEquals(emptyList(), b.recorded)
    }

    @Test
    fun declinedCallIsLoggedOnceByTheCallerOnly() = runTest {
        val (a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.decline()
        runCurrent()
        assertEquals(CallEventDescriptor(CallOutcome.Declined, video = false), a.single())
        assertEquals(emptyList(), b.recorded)
    }

    @Test
    fun ringTimeoutIsLoggedAsNoAnswerOnceByTheCallerOnly() = runTest {
        val (a, b) = pair()
        a.controller.startCall(sam, video = true)
        advanceTimeBy(RING_TIMEOUT_MS + 1)
        assertEquals(CallEventDescriptor(CallOutcome.NoAnswer, video = true), a.single())
        assertEquals(emptyList(), b.recorded)
    }

    @Test
    fun cancelledCallIsLoggedOnceByTheCallerOnly() = runTest {
        val (a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        a.controller.hangup()
        runCurrent()
        assertEquals(CallEventDescriptor(CallOutcome.Cancelled, video = false), a.single())
        assertEquals(emptyList(), b.recorded)
    }

    @Test
    fun connectFailureBeforeTheCallConnectsIsLoggedAsFailed() = runTest {
        val (a, b) = pair(FakeCallScript(failConnect = true))
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        runCurrent()
        advanceTimeBy(CONNECT_TIMEOUT_MS + 1)
        assertEquals(CallEventDescriptor(CallOutcome.Failed, video = false), a.single())
        assertEquals(emptyList(), b.recorded)
    }

    @Test
    fun eachCallInASequenceGetsExactlyOneEntryAndAnEndedReEmitAddsNone() = runTest {
        val (a, b) = pair()
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.decline()
        runCurrent()
        a.controller.hangup()
        a.controller.setMuted(true)
        runCurrent()
        assertEquals(1, a.recorded.size)

        a.controller.startCall(sam, video = true)
        runCurrent()
        a.controller.hangup()
        runCurrent()
        assertEquals(listOf(CallOutcome.Declined, CallOutcome.Cancelled), a.recorded.map { it.second.outcome })
        assertEquals(emptyList(), b.recorded)
    }

    @Test
    fun anEndedStateEmittedAgainWithTheSameCallIdIsNotRecordedTwice() = runTest {
        val ended = CallState(
            phase = CallPhase.Ended(CallEndReason.Declined),
            callId = "c1",
            peer = sam,
            direction = CallDirection.Outgoing,
        )
        val states = MutableStateFlow(ended)
        val recorded = mutableListOf<CallEventDescriptor>()
        backgroundScope.launch { recordCallEvents(states) { _, e -> recorded += e } }
        runCurrent()
        states.value = ended.copy(quality = CallQuality.Poor)
        runCurrent()
        assertEquals(1, recorded.size)
        states.value = ended.copy(callId = "c2")
        runCurrent()
        assertEquals(2, recorded.size)
    }

    @Test
    fun aFailingRecordEventDoesNotStopLaterCallsFromBeingLogged() = runTest {
        val states = MutableStateFlow(CallState())
        val recorded = mutableListOf<String>()
        backgroundScope.launch {
            recordCallEvents(states) { _, e -> if (recorded.isEmpty()) { recorded += "boom"; error("send failed") } else recorded += e.outcome.name }
        }
        val base = CallState(callId = "c1", peer = sam, direction = CallDirection.Outgoing)
        states.value = base.copy(phase = CallPhase.Ended(CallEndReason.Declined))
        runCurrent()
        states.value = base.copy(callId = "c2", phase = CallPhase.Ended(CallEndReason.Missed))
        runCurrent()
        assertEquals(listOf("boom", "NoAnswer"), recorded)
    }

    @Test
    fun aCallThatEndsWhileTheLastOneIsStillBeingLoggedIsLoggedToo() = runTest {
        val net = LoopbackSignaling(now = { testScheduler.currentTime })
        val caller = CallController(
            selfOdinId = frodo,
            signaling = net.endpoint(frodo),
            engineFactory = CallEngineFactory { _ -> FakeCallEngine(backgroundScope, FakeCallScript(), peerLabel = "peer", selfLabel = frodo) },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )
        val recorded = mutableListOf<String>()
        backgroundScope.launch {
            recordCallEvents(caller.transitions) { _, e -> delay(1_000); recorded += e.outcome.name }
        }
        for (callId in listOf("c1", "c2")) {
            caller.startCall(sam, video = false, callId = callId)
            runCurrent()
            caller.hangup()
            runCurrent()
        }
        caller.startCall(sam, video = false, callId = "c3")
        runCurrent()
        advanceTimeBy(5_000)

        assertEquals(listOf("Cancelled", "Cancelled"), recorded)
    }
}
