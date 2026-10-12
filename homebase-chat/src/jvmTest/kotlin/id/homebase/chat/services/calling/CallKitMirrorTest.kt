package id.homebase.chat.services.calling

import id.homebase.core.notifications.CallKitActions
import id.homebase.core.notifications.CallKitBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CallKitMirrorTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"

    /**
     * What the iOS Simulator does: callservicesd cannot open the in-call UI (facetime://?showInCallUI=1 fails), logs
     * "Disconnecting call because there wont be a UI to host the call" and sends CXEndCallAction about a second after
     * CXStartCallAction. The real device keeps the call.
     */
    private class CallKitHost(
        override val hostsOutgoingCalls: Boolean,
        private val disconnectsUnhostedCalls: Boolean,
        private val scope: TestScope,
        private val actions: () -> CallKitActions,
    ) : CallKitBridge {
        val log = mutableListOf<String>()

        override fun reportOutgoing(callId: String, handle: String, video: Boolean) {
            log += "outgoing:$callId"
            if (disconnectsUnhostedCalls) scope.backgroundScope.launch {
                delay(1_200)
                log += "CXEndCallAction:$callId"
                actions().endOrDecline(callId)
            }
        }

        override fun reportConnected(callId: String) { log += "connected:$callId" }
        override fun reportEnded(callId: String, remoteEnded: Boolean) { log += "ended:$callId:remote=$remoteEnded" }
    }

    private class Rig(scope: TestScope, hosts: Boolean, disconnectsUnhosted: Boolean) {
        val net = LoopbackSignaling(now = { scope.testScheduler.currentTime })
        val caller = CallController(
            "frodo.baggins.demo.rocks", net.endpoint("frodo.baggins.demo.rocks"),
            CallEngineFactory { _ -> FakeCallEngine(scope.backgroundScope) }, scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
        )
        val callee = CallController(
            "samwise.gamgee.demo.rocks", net.endpoint("samwise.gamgee.demo.rocks"),
            CallEngineFactory { _ -> FakeCallEngine(scope.backgroundScope) }, scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
        )
        val callKit: CallKitHost = CallKitHost(hosts, disconnectsUnhosted, scope) {
            object : CallKitActions {
                override fun accept(callId: String) = caller.accept()
                override fun endOrDecline(callId: String) = caller.hangup()
                override fun setMuted(muted: Boolean) = caller.setMuted(muted)
            }
        }

        init {
            scope.backgroundScope.launch { CallKitMirror(caller) { callKit }.run() }
        }
    }

    @Test
    fun aHostThatCannotCarryOutgoingCallsIsNeverToldAboutThem_soTheCallSurvivesTheSimulator() = runTest {
        val rig = Rig(this, hosts = false, disconnectsUnhosted = true)
        rig.caller.startCall(sam, video = false)
        advanceTimeBy(5_000)

        assertEquals(CallPhase.Outgoing, rig.caller.state.value.phase)
        assertTrue(rig.callKit.log.isEmpty(), "nothing is reported to a host that disconnects it: ${rig.callKit.log}")
        assertTrue(rig.net.sent.none { it.signal is CallSignal.Hangup })

        rig.callee.accept()
        advanceTimeBy(1_500)
        assertEquals(CallPhase.Connected, rig.caller.state.value.phase)
        assertTrue(rig.callKit.log.isEmpty())
    }

    @Test
    fun registeringAnOutgoingCallWithAHostThatDisconnectsItCancelsTheCallerOwnCall() = runTest {
        val rig = Rig(this, hosts = true, disconnectsUnhosted = true)
        rig.caller.startCall(sam, video = false)
        advanceTimeBy(5_000)

        assertEquals(CallPhase.Ended(CallEndReason.Cancelled), rig.caller.state.value.phase)
        assertEquals(1, rig.callKit.log.count { it.startsWith("CXEndCallAction") })
        assertTrue(rig.net.sent.any { it.from == frodo && it.signal is CallSignal.Hangup && (it.signal as CallSignal.Hangup).reason == HangupReason.Cancelled })
    }

    @Test
    fun aHostThatCarriesOutgoingCallsSeesStartConnectAndEnd() = runTest {
        val rig = Rig(this, hosts = true, disconnectsUnhosted = false)
        rig.caller.startCall(sam, video = false, callId = "c-dev")
        runCurrent()
        rig.callee.accept()
        advanceTimeBy(1_500)
        rig.caller.hangup()
        runCurrent()

        assertEquals(listOf("outgoing:c-dev", "connected:c-dev", "ended:c-dev:remote=true"), rig.callKit.log)
    }
}
