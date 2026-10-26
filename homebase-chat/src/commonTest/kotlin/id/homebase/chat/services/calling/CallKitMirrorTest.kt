package id.homebase.chat.services.calling

import id.homebase.core.notifications.CallKitBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

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
        private val actions: CallKitActionsAdapter,
    ) : CallKitBridge {
        val log = mutableListOf<String>()

        override fun reportOutgoing(callId: String, handle: String, video: Boolean) {
            log += "outgoing:$callId"
            if (disconnectsUnhostedCalls) scope.backgroundScope.launch {
                delay(1_200)
                log += "CXEndCallAction:$callId"
                actions.endOrDecline(callId)
            }
        }

        override fun reportConnected(callId: String) { log += "connected:$callId" }
        override fun reportVideo(callId: String, video: Boolean) { log += "video:$callId:$video" }
        override fun reportCaller(callId: String, label: String) { log += "caller:$callId:$label" }
        override fun reportEnded(callId: String, remoteEnded: Boolean) { log += "ended:$callId:remote=$remoteEnded" }
        override fun reportEndedElsewhere(callId: String, declined: Boolean) { log += "elsewhere:$callId:declined=$declined" }
        override fun requestAnswer(callId: String) { log += "answer:$callId" }
        override fun requestEnd(callId: String) { log += "end-request:$callId" }
    }

    private class Rig(scope: TestScope, hosts: Boolean = true, disconnectsUnhosted: Boolean = false) {
        val net = LoopbackSignaling(now = { scope.testScheduler.currentTime })
        val caller = controller(scope, "frodo.baggins.demo.rocks")
        val callee = controller(scope, "samwise.gamgee.demo.rocks")
        val callerKit = CallKitHost(hosts, disconnectsUnhosted, scope, CallKitActionsAdapter(caller))
        val calleeKit = CallKitHost(hosts, disconnectsUnhosted, scope, CallKitActionsAdapter(callee))
        val calleeActions = CallKitActionsAdapter(callee)

        private fun controller(scope: TestScope, id: String) = CallController(
            id, net.endpoint(id), CallEngineFactory { _ -> FakeCallEngine(scope.backgroundScope) }, scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
        )

        init {
            scope.backgroundScope.launch { CallKitMirror(caller) { callerKit }.run() }
            scope.backgroundScope.launch { CallKitMirror(callee) { calleeKit }.run() }
        }
    }

    @Test
    fun aHostThatCannotCarryOutgoingCallsIsNeverToldAboutThem_soTheCallSurvivesTheSimulator() = runTest {
        val rig = Rig(this, hosts = false, disconnectsUnhosted = true)
        rig.caller.startCall(sam, video = false)
        advanceTimeBy(5_000)

        assertEquals(CallPhase.Outgoing, rig.caller.state.value.phase)
        assertTrue(rig.callerKit.log.isEmpty(), "nothing is reported to a host that disconnects it: ${rig.callerKit.log}")
        assertTrue(rig.net.sent.none { it.signal is CallSignal.Hangup })

        rig.callee.accept()
        advanceTimeBy(1_500)
        assertEquals(CallPhase.Connected, rig.caller.state.value.phase)
        assertTrue(rig.callerKit.log.isEmpty())
    }

    @Test
    fun registeringAnOutgoingCallWithAHostThatDisconnectsItCancelsTheCallerOwnCall() = runTest {
        val rig = Rig(this, hosts = true, disconnectsUnhosted = true)
        rig.caller.startCall(sam, video = false)
        advanceTimeBy(5_000)

        assertEquals(CallPhase.Ended(CallEndReason.Cancelled), rig.caller.state.value.phase)
        assertEquals(1, rig.callerKit.log.count { it.startsWith("CXEndCallAction") })
        assertTrue(rig.net.sent.any { it.from == frodo && it.signal is CallSignal.Hangup && it.signal.reason == HangupReason.Cancelled })
    }

    @Test
    fun aHostThatCarriesOutgoingCallsSeesStartConnectAndALocalEndAsAnEndRequest() = runTest {
        val rig = Rig(this)
        rig.caller.startCall(sam, video = false, callId = "c-dev")
        runCurrent()
        rig.callee.accept()
        advanceTimeBy(1_500)
        rig.caller.hangup()
        runCurrent()

        assertEquals(listOf("outgoing:c-dev", "connected:c-dev", "end-request:c-dev"), rig.callerKit.log)
    }

    @Test
    fun anIncomingVideoCallIsUpdatedToVideoOnCallKit_butAnAudioCallIsNot() = runTest {
        for (video in listOf(true, false)) {
            val rig = Rig(this)
            rig.caller.startCall(sam, video = video, callId = "c-in-$video")
            runCurrent()

            assertEquals(if (video) listOf("video:c-in-true:true") else emptyList(), rig.calleeKit.log)
        }
    }

    @Test
    fun acceptingARingingCallInsideTheAppAsksCallKitToAnswerIt_soTheAudioSessionActivates() = runTest {
        val rig = Rig(this)
        rig.caller.startCall(sam, video = false, callId = "c-app")
        runCurrent()
        assertTrue(rig.calleeKit.log.none { it.startsWith("answer") })

        rig.callee.accept()
        advanceTimeBy(1_500)

        assertEquals(CallPhase.Connected, rig.callee.state.value.phase)
        assertEquals(listOf("answer:c-app"), rig.calleeKit.log)
    }

    @Test
    fun anIncomingCallTheUserDeclinedInTheAppIsEndedThroughCallKit_notReportedAsRemoteEnded() = runTest {
        val rig = Rig(this)
        rig.caller.startCall(sam, video = false, callId = "c-dec")
        runCurrent()
        rig.callee.decline()
        runCurrent()

        assertEquals(listOf("end-request:c-dec"), rig.calleeKit.log)
    }

    @Test
    fun anIncomingCallTheCallerCancelsIsReportedToCallKitAsRemoteEnded() = runTest {
        val rig = Rig(this)
        rig.caller.startCall(sam, video = false, callId = "c-can")
        runCurrent()
        rig.caller.hangup()
        runCurrent()

        assertEquals(listOf("ended:c-can:remote=true"), rig.calleeKit.log)
        assertFalse(rig.calleeKit.log.any { it.startsWith("end-request") })
    }

    @Test
    fun answeringFromCallKitReachesConnectedOnTheCallee() = runTest {
        val rig = Rig(this)
        rig.caller.startCall(sam, video = false, callId = "c-ans")
        runCurrent()

        rig.calleeActions.accept("c-ans")
        advanceTimeBy(1_500)

        assertEquals(CallPhase.Connected, rig.callee.state.value.phase)
        assertEquals(CallPhase.Connected, rig.caller.state.value.phase)
    }

    @Test
    fun endingFromCallKitWhileRingingSendsADeclineHangup() = runTest {
        val rig = Rig(this)
        rig.caller.startCall(sam, video = false, callId = "c-end")
        runCurrent()

        rig.calleeActions.endOrDecline("c-end")
        runCurrent()

        assertEquals(CallPhase.Ended(CallEndReason.Declined), rig.callee.state.value.phase)
        assertTrue(rig.net.sent.any { it.from == sam && (it.signal as? CallSignal.Hangup)?.reason == HangupReason.Declined })
        assertEquals(CallPhase.Ended(CallEndReason.Declined), rig.caller.state.value.phase)
    }

    @Test
    fun endingFromCallKitWhileConnectedSendsANormalHangup() = runTest {
        val rig = Rig(this)
        rig.caller.startCall(sam, video = false, callId = "c-hup")
        runCurrent()
        rig.callee.accept()
        advanceTimeBy(1_500)

        rig.calleeActions.endOrDecline("c-hup")
        runCurrent()

        assertEquals(CallPhase.Ended(CallEndReason.Hangup(remote = false)), rig.callee.state.value.phase)
        assertTrue(rig.net.sent.any { it.from == sam && (it.signal as? CallSignal.Hangup)?.reason == HangupReason.Normal })
        assertEquals(CallPhase.Ended(CallEndReason.Hangup(remote = true)), rig.caller.state.value.phase)
    }

    @Test
    fun anEndForACallThatIsNotTheCurrentOneIsIgnored() = runTest {
        val rig = Rig(this)
        rig.caller.startCall(sam, video = false, callId = "c-cur")
        runCurrent()

        rig.calleeActions.endOrDecline("some-other-call")
        runCurrent()

        assertEquals(CallPhase.Incoming, rig.callee.state.value.phase)
    }

    @Test
    fun muteFromCallKitReachesTheController() = runTest {
        val rig = Rig(this)
        rig.caller.startCall(sam, video = false, callId = "c-mute")
        runCurrent()
        rig.callee.accept()
        advanceTimeBy(1_500)

        rig.calleeActions.setMuted(true)
        runCurrent()

        assertTrue(rig.callee.state.value.media.muted)
    }

    @Test
    fun aCallKitAnswerForAnEarlierCallDoesNotAnswerTheOneRingingNow() = runTest {
        val rig = Rig(this)
        rig.caller.startCall(sam, video = false, callId = "c-now")
        runCurrent()
        rig.calleeActions.accept("c-before")
        runCurrent()
        assertEquals(CallPhase.Incoming, rig.callee.state.value.phase)
        rig.calleeActions.accept("c-now")
        runCurrent()
        assertTrue(rig.callee.state.value.phase != CallPhase.Incoming)
    }
}
