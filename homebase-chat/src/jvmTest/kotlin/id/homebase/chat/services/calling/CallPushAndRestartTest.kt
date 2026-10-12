package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallPushAndRestartTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"
    private val machine = CallStateMachine(frodo)
    private val c = "c1"

    private fun step(s: CallState, e: CallEvent, now: Long = 1_000) = machine.reduce(s, e, now)
    private fun fromSam(sig: CallSignal, at: Long = 1_000) = CallEvent.Signal(InboundSignal(sam, sig, at))

    @Test
    fun pushRingStartsIncomingWithoutSdpAndOfferCollapsesIntoIt() {
        val pushed = step(CallState(), CallEvent.PushRing(c, sam))
        assertEquals(CallPhase.Incoming, pushed.state.phase)
        assertNull(pushed.state.pendingOfferSdp)
        assertEquals(listOf(CallEffect.StartTimer(CallTimer.Ring, c)), pushed.effects)

        val offered = step(pushed.state, fromSam(CallSignal.Offer(c, "sdp", true)))
        assertEquals("sdp", offered.state.pendingOfferSdp)
        assertEquals(c, offered.state.callId)
        assertEquals(listOf(CallEffect.SendSignal(sam, CallSignal.Ringing(c))), offered.effects)
    }

    @Test
    fun duplicatePushRingForTheSameCallIsIgnored() {
        val pushed = step(CallState(), CallEvent.PushRing(c, sam)).state
        assertEquals(CallTransition(pushed), step(pushed, CallEvent.PushRing(c, sam)))
    }

    @Test
    fun acceptBeforeTheOfferArrivesAnswersOnceItDoes() {
        val pushed = step(CallState(), CallEvent.PushRing(c, sam)).state
        val accepted = step(pushed, CallEvent.UserAccept)
        assertTrue(accepted.state.acceptPending)
        assertEquals(CallPhase.Incoming, accepted.state.phase)
        assertEquals(emptyList<CallEffect>(), accepted.effects)

        val elsewhereSignal = CallSignal.Hangup(c, HangupReason.AnsweredElsewhere)
        val offered = step(accepted.state, fromSam(CallSignal.Offer(c, "sdp", false)))
        assertEquals(CallPhase.Connecting, offered.state.phase)
        assertTrue(CallEffect.CreateAnswer("sdp", false) in offered.effects)
        assertTrue(CallEffect.SendSignal(frodo, elsewhereSignal) in offered.effects)

        val echoed = step(offered.state, CallEvent.Signal(InboundSignal(frodo, elsewhereSignal, 1_000)))
        assertEquals(CallPhase.Connecting, echoed.state.phase)
        assertEquals(CallPhase.Connecting, step(echoed.state, CallEvent.PushHangup(c)).state.phase)
    }

    @Test
    fun pushHangupRetractsAnIncomingRingOnly() {
        val pushed = step(CallState(), CallEvent.PushRing(c, sam)).state
        assertEquals(CallPhase.Ended(CallEndReason.Cancelled), step(pushed, CallEvent.PushHangup(c)).state.phase)
        assertEquals(CallTransition(pushed), step(pushed, CallEvent.PushHangup("other")))
    }

    @Test
    fun pushRingIsRefusedWhileBusy() {
        val busy = step(CallState(), CallEvent.UserCall("mine", sam, false)).state
        assertEquals(CallTransition(busy), step(busy, CallEvent.PushRing("other", "merry.demo.rocks")))
    }

    @Test
    fun restartSignalsAreAnsweredOnlyOnAnEstablishedCall() {
        val outgoing = step(CallState(), CallEvent.UserCall(c, sam, false)).state
        val connecting = step(outgoing, fromSam(CallSignal.Answer(c, "a"))).state
        assertEquals(
            listOf(CallEffect.AnswerRestart("r")),
            step(connecting, fromSam(CallSignal.Restart(c, "r"))).effects,
        )
        assertEquals(CallTransition(connecting), step(connecting, fromSam(CallSignal.RestartAnswer(c, "ra"))))
        val reconnecting = step(step(connecting, CallEvent.Media(MediaEvent.Connected)).state, CallEvent.Media(MediaEvent.Disconnected)).state
        assertEquals(
            listOf(CallEffect.ApplyRemoteAnswer("ra")),
            step(reconnecting, fromSam(CallSignal.RestartAnswer(c, "ra"))).effects,
        )
        assertEquals(CallTransition(outgoing), step(outgoing, fromSam(CallSignal.Restart(c, "r"))))
    }

    @Test
    fun onlyTheOriginalCallerDrivesIceRestart() {
        val incoming = step(CallState(), fromSam(CallSignal.Offer(c, "o", false))).state
        val accepted = step(incoming, CallEvent.UserAccept).state
        val connected = step(accepted, CallEvent.Media(MediaEvent.Connected)).state
        val dropped = step(connected, CallEvent.Media(MediaEvent.Disconnected))
        assertEquals(listOf<CallEffect>(CallEffect.StartTimer(CallTimer.Reconnect, c)), dropped.effects)
    }

    @Test
    fun channelKeyIsDeterministicPerIdentityAndCaseInsensitive() {
        assertEquals(CallChannel.keyFor(sam), CallChannel.keyFor(sam.uppercase()))
        assertNotEquals(CallChannel.keyFor(sam), CallChannel.keyFor(frodo))
        assertEquals(36, CallChannel.keyFor(sam).length)
    }

    @Test
    fun offerCarriesRingPushAndPreAnswerHangupRetractsItWithTheSameTag() {
        val offer = CallChannel.outbound(sam, CallSignal.Offer(c, "sdp", false), "Frodo")
        assertEquals(CallChannel.keyFor(sam), offer.channelKey)
        assertEquals(CallPush(CallPushKind.Ring, CallChannel.RING_TYPE_ID, c, "Frodo is calling"), offer.push)

        val cancel = CallChannel.outbound(sam, CallSignal.Hangup(c, HangupReason.Cancelled), "Frodo")
        assertEquals(CallPushKind.Hangup, cancel.push?.kind)
        assertEquals(c, cancel.push?.tagId)

        assertNull(CallChannel.outbound(sam, CallSignal.Hangup(c, HangupReason.Normal), "Frodo").push)
        assertNull(CallChannel.outbound(sam, CallSignal.Hangup(c, HangupReason.Declined), "Frodo").push)
        assertNull(CallChannel.outbound(sam, CallSignal.Ice(c, "cand"), "Frodo").push)
        assertNull(CallChannel.outbound(sam, CallSignal.Ringing(c), "Frodo").push)
    }

    @Test
    fun verificationCodeMatchesOnBothSidesAndChangesWithAFingerprint() {
        fun sdp(fp: String) = "v=0\r\na=fingerprint:sha-256 $fp\r\n"
        val offer = sdp("AA:BB:CC")
        val answer = sdp("11:22:33")
        val code = assertNotNull(CallVerification.code(offer, answer))
        assertEquals(code, CallVerification.code(answer, offer))
        assertNotEquals(code, CallVerification.code(offer, sdp("11:22:34")))
        assertTrue(Regex("\\d{3} \\d{3}").matches(code))
        assertNull(CallVerification.code("v=0", answer))
    }
}

class CallEventMappingTest {
    private fun ended(reason: CallEndReason, connectedAt: Long? = null) =
        CallState(phase = CallPhase.Ended(reason), callId = "c", peer = "p", direction = CallDirection.Outgoing, connectedAtMs = connectedAt)

    @Test
    fun placedMediaNotCurrentCameraStateDecidesVideo() {
        val state = ended(CallEndReason.Declined).copy(placedWithVideo = true)
        assertEquals(true, callEventFor(CallEndReason.Declined, state, 0)?.video)
    }

    @Test
    fun connectedCallLogsDurationWhoeverHungUp() {
        val e = callEventFor(CallEndReason.Hangup(remote = true), ended(CallEndReason.Hangup(true), 1_000), 66_000)
        assertEquals(id.homebase.chat.calling.CallEventDescriptor(id.homebase.chat.calling.CallOutcome.Completed, false, 65), e)
    }

    @Test
    fun unansweredOutcomesMapAndPreConnectFailuresAreLoggedAsFailed() {
        assertEquals(id.homebase.chat.calling.CallOutcome.NoAnswer, callEventFor(CallEndReason.Missed, ended(CallEndReason.Missed), 0)?.outcome)
        assertEquals(id.homebase.chat.calling.CallOutcome.Declined, callEventFor(CallEndReason.Declined, ended(CallEndReason.Declined), 0)?.outcome)
        assertEquals(id.homebase.chat.calling.CallOutcome.Busy, callEventFor(CallEndReason.Busy, ended(CallEndReason.Busy), 0)?.outcome)
        assertEquals(id.homebase.chat.calling.CallOutcome.Failed, callEventFor(CallEndReason.Failed(FailReason.NoRoute), ended(CallEndReason.Failed(FailReason.NoRoute)), 0)?.outcome)
    }
}
