package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

// An Error from the engine constructor (missing native lib) must end the call, not kill the inbox loop.
class CallEngineCreateFailureTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"

    private fun TestScope.controller(net: LoopbackSignaling, failures: Int): CallController {
        var created = 0
        return CallController(
            selfOdinId = frodo,
            signaling = net.endpoint(frodo),
            engineFactory = CallEngineFactory { _ ->
                if (created++ < failures) throw UnsatisfiedLinkError("Native library 'libwebrtc-java-macos-aarch64.dylib' is not on the classpath")
                FakeCallEngine(backgroundScope, FakeCallScript(), peerLabel = "peer", selfLabel = frodo)
            },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            deviceId = "frodo",
        )
    }

    @Test
    fun anEngineThatCannotLoadFailsTheCallAndTheControllerTakesTheNextOne() = runTest {
        val net = LoopbackSignaling(now = { testScheduler.currentTime })
        val c = controller(net, failures = 1)

        c.startCall(sam, video = false)
        runCurrent()

        assertEquals(CallPhase.Ended(CallEndReason.Failed(FailReason.Other)), c.state.value.phase)
        val callId = c.state.value.callId!!
        assertTrue(net.sent.any { it.from == frodo && it.signal == CallSignal.Hangup(callId, HangupReason.Normal, device = "frodo") })

        c.startCall(sam, video = false)
        runCurrent()
        assertEquals(CallPhase.Outgoing, c.state.value.phase)
        c.hangup()
        runCurrent()
        assertEquals(CallPhase.Ended(CallEndReason.Cancelled), c.state.value.phase)
    }

    @Test
    fun aHangupQueuedBehindTheFailingEngineEndsTheCall() = runTest {
        val net = LoopbackSignaling(now = { testScheduler.currentTime })
        val c = controller(net, failures = Int.MAX_VALUE)

        c.startCall(sam, video = true)
        c.hangup()
        runCurrent()

        assertEquals(CallPhase.Ended(CallEndReason.Cancelled), c.state.value.phase)
    }
}
