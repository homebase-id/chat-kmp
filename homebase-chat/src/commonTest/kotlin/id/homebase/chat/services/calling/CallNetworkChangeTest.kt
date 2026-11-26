package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallNetworkChangeTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"
    private val machine = CallStateMachine(frodo)
    private val c = "c1"

    private fun step(s: CallState, e: CallEvent) = machine.reduce(s, e, 1_000)
    private fun fromSam(sig: CallSignal) = CallEvent.Signal(InboundSignal(sam, sig, 0))
    private val connectedMedia = CallEvent.Media(MediaEvent.Connected)

    private val outgoing = step(CallState(), CallEvent.UserCall(c, sam, false)).state
    private val callerConnecting = step(outgoing, fromSam(CallSignal.Answer(c, "ans"))).state
    private val callerConnected = step(callerConnecting, connectedMedia).state
    private val callerReconnecting = step(callerConnected, CallEvent.Media(MediaEvent.Disconnected)).state

    private val incoming = step(CallState(), fromSam(CallSignal.Offer(c, "sdp", false))).state
    private val calleeConnecting = step(incoming, CallEvent.UserAccept).state
    private val calleeConnected = step(calleeConnecting, connectedMedia).state
    private val calleeReconnecting = step(calleeConnected, CallEvent.Media(MediaEvent.Disconnected)).state
    private val callerRestarted = step(callerConnected, CallEvent.NetworkChanged).state

    @Test
    fun connectedCallerRestartsIceAndStaysConnected() {
        val t = step(callerConnected, CallEvent.NetworkChanged)
        assertEquals(listOf<CallEffect>(CallEffect.RestartIce), t.effects)
        assertEquals(CallPhase.Connected, t.state.phase)
        assertTrue(t.state.restartPending)
    }

    @Test
    fun calleeAsksTheCallerToRestart() {
        listOf(calleeConnected, calleeReconnecting).forEach {
            val t = step(it, CallEvent.NetworkChanged)
            assertEquals(listOf<CallEffect>(CallEffect.SendSignal(sam, CallSignal.RestartRequest(c))), t.effects, "in ${it.phase}")
            assertEquals(it, t.state)
        }
    }

    @Test
    fun aPendingRestartIsResentNotLeftToStall() {
        listOf(callerReconnecting, callerRestarted).forEach {
            assertTrue(it.restartPending)
            assertEquals(CallTransition(it, listOf(CallEffect.RestartIce)), step(it, CallEvent.NetworkChanged), "in ${it.phase}")
        }
    }

    @Test
    fun theAnsweredRestartLetsTheNextChangeRestartAgain() {
        val answered = step(callerRestarted, fromSam(CallSignal.RestartAnswer(c, "ans2"))).state
        assertEquals(listOf<CallEffect>(CallEffect.RestartIce), step(answered, CallEvent.NetworkChanged).effects)
    }

    @Test
    fun outsideAnEstablishedCallTheChangeIsIgnored() {
        val ended = step(callerConnected, CallEvent.UserHangup).state
        listOf(CallState(), outgoing, incoming, callerConnecting, calleeConnecting, ended).forEach {
            assertEquals(CallTransition(it), step(it, CallEvent.NetworkChanged), "in ${it.phase}")
        }
    }
}
