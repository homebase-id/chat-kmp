package id.homebase.chat.services.calling

import id.homebase.core.notifications.CallKitBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CallKitRingRouterTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"

    private class Bridge : CallKitBridge {
        override val hostsOutgoingCalls = true
        val ended = mutableListOf<String>()
        override fun reportOutgoing(callId: String, handle: String, video: Boolean) = Unit
        override fun reportConnected(callId: String) = Unit
        override fun reportVideo(callId: String, video: Boolean) = Unit
        override fun reportEnded(callId: String, remoteEnded: Boolean) { ended += callId }
        override fun reportEndedElsewhere(callId: String, declined: Boolean) { ended += callId }
        override fun requestAnswer(callId: String) = Unit
        override fun requestEnd(callId: String) = Unit
    }

    private class Rig(scope: TestScope) {
        val bridge = Bridge()
        val controller = CallController(
            "frodo.baggins.demo.rocks",
            LoopbackSignaling(now = { scope.testScheduler.currentTime }).endpoint("frodo.baggins.demo.rocks"),
            CallEngineFactory { _ -> FakeCallEngine(scope.backgroundScope) }, scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
        )
        val router = CallKitRingRouter(
            target = object : CallRingPushTarget {
                override fun pushRing(callId: String, from: String) = controller.pushRing(callId, from)
                override fun onPushHangup(callId: String) = controller.onPushHangup(callId)
            },
            bridge = { bridge },
            scope = scope.backgroundScope,
        )
    }

    @Test
    fun aRingThatStartsRingingKeepsItsCallKitCall() = runTest {
        val rig = Rig(this)
        rig.router.onRing("c1", sam, null)
        runCurrent()

        assertEquals(CallPhase.Incoming, rig.controller.state.value.phase)
        assertEquals(emptyList(), rig.bridge.ended)
    }

    @Test
    fun aReplayedRingForACallThatAlreadyEndedEndsItsCallKitCall() = runTest {
        val rig = Rig(this)
        rig.router.onRing("c1", sam, null)
        runCurrent()
        rig.router.onHangup("c1", sam)
        runCurrent()
        assertEquals(emptyList(), rig.bridge.ended)

        rig.router.onRing("c1", sam, null)
        runCurrent()

        assertEquals(listOf("c1"), rig.bridge.ended)
    }

    @Test
    fun aRingRefusedBecauseTheLineIsBusyEndsOnlyThatCallsCallKitCall() = runTest {
        val rig = Rig(this)
        rig.router.onRing("c1", sam, null)
        runCurrent()

        rig.router.onRing("c2", "merry.demo.rocks", null)
        runCurrent()

        assertEquals(listOf("c2"), rig.bridge.ended)
        assertEquals("c1", rig.controller.state.value.callId)
    }

    @Test
    fun aRingFromOurselfEndsItsCallKitCall() = runTest {
        val rig = Rig(this)
        rig.router.onRing("c3", frodo, null)
        runCurrent()

        assertEquals(listOf("c3"), rig.bridge.ended)
    }
}
