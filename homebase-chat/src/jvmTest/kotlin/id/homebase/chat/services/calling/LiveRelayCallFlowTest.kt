package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LiveRelayCallFlowTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"
    private val gimli = "gimli.demo.rocks"
    private val candidates = List(4) { "candidate:$it 1 udp 2122260223 192.0.2.$it 50000 typ host" }

    private class Peer(val controller: CallController, val engines: MutableList<FakeCallEngine>) {
        val state get() = controller.state.value
    }

    private suspend fun CoroutineScope.peer(
        relay: LastValueRelay,
        identity: String,
        device: LastValueRelay.Device,
        script: FakeCallScript = FakeCallScript(),
    ): Peer {
        val engines = mutableListOf<FakeCallEngine>()
        val scope = this
        val controller = CallController(
            selfOdinId = identity,
            signaling = LiveRelayCallSignaling(identity, relay.relayFor(identity), device.bus),
            engineFactory = CallEngineFactory { _ -> FakeCallEngine(scope, script, "peer", identity).also { engines += it } },
            scope = scope,
            offerRepublishMs = 30,
        )
        return Peer(controller, engines)
    }

    private suspend fun await(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(10) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what")
        }
    }

    private fun flow(body: suspend CoroutineScope.(LastValueRelay) -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                scope.body(LastValueRelay())
            } finally {
                scope.cancel()
            }
        }
    }

    private fun CallSignal?.candidateCount() = (this as? CallSignal.Offer)?.sdp?.lines()?.count { it.startsWith("a=candidate:") }

    @Test
    fun pushWokenCalleeFindsTheCompleteOfferBecauseTheCallerNeverTricklesWhileUnanswered() = flow { relay ->
        val caller = peer(relay, frodo, relay.device(frodo, online = true), FakeCallScript(localCandidates = candidates))
        val sleeping = relay.device(sam, online = false)

        caller.controller.startCall(sam, video = false, callId = "c-woken")
        await("republished offer carrying every candidate") { relay.lastRetained(sam, frodo).candidateCount() == candidates.size }
        delay(150)

        val sent = relay.signalsFrom(frodo)
        assertTrue(sent.all { it is CallSignal.Offer }, "only offers while unanswered, got $sent")
        val posts = relay.posts.filter { it.sender == frodo }
        assertEquals("ring", posts.first().body.getValue("push").jsonObject.getValue("kind").jsonPrimitive.content)
        assertTrue(posts.drop(1).all { "push" !in it.body.keys }, "a re-published offer must not ring again")
        assertEquals((1 until sent.size).toList(), sent.drop(1).map { (it as CallSignal.Offer).seq })

        val woken = peer(relay, sam, sleeping)
        woken.controller.onPushRing("c-woken", frodo)
        relay.connect(sleeping)
        await("incoming call with the offer") { woken.state.pendingOfferSdp != null }

        assertEquals(CallPhase.Incoming, woken.state.phase)
        assertEquals("c-woken", woken.state.callId)
        val held = woken.state.pendingOfferSdp!!.lines()
        candidates.forEach { assertTrue("a=$it" in held, "offer must carry $it") }

        woken.controller.accept()
        await("both sides connected") { caller.state.phase == CallPhase.Connected && woken.state.phase == CallPhase.Connected }
        assertEquals(candidates.toSet(), woken.engines.single().addedIce.toSet(), "held candidates are flushed once the answer picked this device")
    }

    @Test
    fun aSecondCalleeDeviceRingingFirstDoesNotMakeTheCallerTrickleOverTheOffer() = flow { relay ->
        val caller = peer(relay, frodo, relay.device(frodo, online = true), FakeCallScript(localCandidates = candidates))
        val ringingNow = peer(relay, sam, relay.device(sam, online = true))
        val sleeping = relay.device(sam, online = false)

        caller.controller.startCall(sam, video = false, callId = "c-two")
        await("foreground device rings straight from the socket") { ringingNow.state.phase == CallPhase.Incoming }
        await("caller sees the Ringing ack") { caller.state.peerRinging }
        await("complete offer retained") { relay.lastRetained(sam, frodo).candidateCount() == candidates.size }
        delay(150)

        assertTrue(relay.signalsFrom(frodo).none { it is CallSignal.Ice }, "no ICE may overwrite the retained offer before an answer")
        assertIs<CallSignal.Offer>(relay.lastRetained(sam, frodo))

        val woken = peer(relay, sam, sleeping)
        woken.controller.onPushRing("c-two", frodo)
        relay.connect(sleeping)
        await("push-woken device holds the offer") { woken.state.pendingOfferSdp != null }
        assertEquals(candidates.size, woken.state.pendingOfferSdp.orEmpty().lines().count { it.startsWith("a=candidate:") })

        woken.controller.accept()
        await("call connects on the woken device") { caller.state.phase == CallPhase.Connected && woken.state.phase == CallPhase.Connected }
        assertTrue(relay.signalsFrom(frodo).any { it is CallSignal.Ice }, "trickle starts once an answer selected a device")
    }

    @Test
    fun anOfferReplayedAfterTheCallEndedNeverRingsOrAnswersBusy() = flow { relay ->
        val caller = peer(relay, frodo, relay.device(frodo, online = true))
        val calleeDevice = relay.device(sam, online = true)
        val callee = peer(relay, sam, calleeDevice)
        val third = peer(relay, gimli, relay.device(gimli, online = true))

        caller.controller.startCall(sam, video = false, callId = "c-old")
        await("callee rings") { callee.state.phase == CallPhase.Incoming }
        callee.controller.decline()
        await("call ended") { callee.state.phase is CallPhase.Ended }

        third.controller.startCall(sam, video = false, callId = "c-new")
        await("a new call rings") { callee.state.callId == "c-new" }

        relay.replay(calleeDevice, frodo, CallSignal.Offer("c-old", "stale", false), CallChannel.keyFor(sam))
        relay.replay(calleeDevice, frodo, CallSignal.Offer("c-old", "stale", false, seq = 3), CallChannel.keyFor(sam))
        delay(200)

        assertEquals("c-new", callee.state.callId)
        assertEquals(CallPhase.Incoming, callee.state.phase)
        assertTrue(
            relay.posts.none { it.sender == sam && it.signal.callId == "c-old" && (it.signal as? CallSignal.Hangup)?.reason == HangupReason.Busy },
            "a replayed offer for an ended call must not be answered with Busy",
        )
        assertNotNull(relay.lastRetained(sam, gimli))
    }
}
