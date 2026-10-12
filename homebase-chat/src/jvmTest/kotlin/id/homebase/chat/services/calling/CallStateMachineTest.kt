package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CallStateMachineTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"
    private val machine = CallStateMachine(frodo)
    private val c = "c1"

    private fun CallStateMachine.step(s: CallState, e: CallEvent, now: Long = 1_000) = reduce(s, e, now)

    private fun signal(from: String, sig: CallSignal, at: Long = 0) = CallEvent.Signal(InboundSignal(from, sig, at))
    private fun fromSam(sig: CallSignal) = signal(sam, sig)

    private val idle = CallState()
    private val outgoing = machine.step(idle, CallEvent.UserCall(c, sam, false)).state
    private val incoming = machine.step(idle, fromSam(CallSignal.Offer(c, "sdp", false)), now = 0).state
    private val connecting = machine.step(outgoing, fromSam(CallSignal.Answer(c, "ans"))).state
    private val connected = machine.step(connecting, CallEvent.Media(MediaEvent.Connected), now = 5_000).state
    private val reconnecting = machine.step(connected, CallEvent.Media(MediaEvent.Disconnected)).state
    private val ended = machine.step(outgoing, CallEvent.UserHangup).state
    private val active = mapOf(
        "outgoing" to outgoing, "incoming" to incoming, "connecting" to connecting,
        "connected" to connected, "reconnecting" to reconnecting,
    )

    private fun assertNoOp(s: CallState, e: CallEvent, now: Long = 1_000) =
        assertEquals(CallTransition(s), machine.step(s, e, now), "no-op expected for $e in ${s.phase}")

    private fun hangupEffects(reason: HangupReason?) = buildList {
        add(CallEffect.CancelTimers)
        if (reason != null) add(CallEffect.SendSignal(sam, CallSignal.Hangup(c, reason)))
        add(CallEffect.ReleaseEngine)
    }

    private fun assertEnds(t: CallTransition, reason: CallEndReason, notify: HangupReason?) {
        assertEquals(CallPhase.Ended(reason), t.state.phase)
        assertEquals(hangupEffects(notify), t.effects)
    }

    @Test
    fun setupStatesAreInTheExpectedPhases() {
        assertEquals(CallPhase.Outgoing, outgoing.phase)
        assertEquals(CallPhase.Incoming, incoming.phase)
        assertEquals(CallPhase.Connecting, connecting.phase)
        assertEquals(CallPhase.Connected, connected.phase)
        assertEquals(CallPhase.Reconnecting, reconnecting.phase)
        assertEquals(CallPhase.Ended(CallEndReason.Cancelled), ended.phase)
    }

    @Test
    fun userCallFromIdleAndEndedStartsOutgoing() {
        listOf(idle, ended).forEach {
            val t = machine.step(it, CallEvent.UserCall(c, sam, true))
            assertEquals(CallPhase.Outgoing, t.state.phase)
            assertEquals(CallDirection.Outgoing, t.state.direction)
            assertEquals(sam, t.state.peer)
            assertEquals(c, t.state.callId)
            assertEquals(true, t.state.media.localVideo)
            assertEquals(listOf(CallEffect.CreateOffer(true), CallEffect.StartTimer(CallTimer.Ring, c)), t.effects)
        }
    }

    @Test
    fun userCallWhileActiveOrToSelfIsIgnored() {
        active.values.forEach { assertNoOp(it, CallEvent.UserCall("c2", "gimli.demo.rocks", false)) }
        assertNoOp(idle, CallEvent.UserCall("c2", frodo, false))
    }

    @Test
    fun offerInIdleRingsAndAcknowledges() {
        val t = machine.step(idle, fromSam(CallSignal.Offer(c, "sdp", true)), now = 0)
        assertEquals(CallPhase.Incoming, t.state.phase)
        assertEquals(CallDirection.Incoming, t.state.direction)
        assertEquals(sam, t.state.peer)
        assertEquals("sdp", t.state.pendingOfferSdp)
        assertEquals(true, t.state.media.remoteVideo)
        assertEquals(
            listOf(CallEffect.SendSignal(sam, CallSignal.Ringing(c)), CallEffect.StartTimer(CallTimer.Ring, c)),
            t.effects,
        )
    }

    @Test
    fun staleOwnAndDuplicateOffersAreIgnored() {
        assertNoOp(idle, signal(sam, CallSignal.Offer(c, "sdp", false), at = 0), now = RING_TIMEOUT_MS + 1)
        assertNoOp(idle, signal(frodo, CallSignal.Offer(c, "sdp", false)))
        assertNoOp(incoming, fromSam(CallSignal.Offer(c, "sdp", false)))
    }

    @Test
    fun offerWhileBusyIsRefusedWithBusyAndStateKept() {
        val gimli = "gimli.demo.rocks"
        listOf(connecting, connected, reconnecting, incoming).forEach {
            val t = machine.step(it, signal(gimli, CallSignal.Offer("c9", "x", false)))
            assertEquals(it, t.state)
            assertEquals(listOf(CallEffect.SendSignal(gimli, CallSignal.Hangup("c9", HangupReason.Busy))), t.effects)
        }
        val t = machine.step(outgoing, signal(gimli, CallSignal.Offer("c9", "x", false)))
        assertEquals(outgoing, t.state)
        assertEquals(listOf(CallEffect.SendSignal(gimli, CallSignal.Hangup("c9", HangupReason.Busy))), t.effects)
    }

    @Test
    fun glareLowerOdinIdKeepsItsOffer() {
        assertNoOp(outgoing, fromSam(CallSignal.Offer("c-sam", "sdp", false)))
    }

    @Test
    fun glareHigherOdinIdRetractsAndAnswers() {
        val samMachine = CallStateMachine(sam)
        val out = samMachine.step(idle, CallEvent.UserCall("c-sam", frodo, false)).state
        val t = samMachine.step(out, signal(frodo, CallSignal.Offer(c, "sdp", true)))
        assertEquals(CallPhase.Connecting, t.state.phase)
        assertEquals(c, t.state.callId)
        assertEquals(CallDirection.Incoming, t.state.direction)
        assertEquals(true, t.state.media.remoteVideo)
        assertEquals(
            listOf(
                CallEffect.CancelTimers,
                CallEffect.SendSignal(frodo, CallSignal.Hangup("c-sam", HangupReason.Cancelled)),
                CallEffect.ReleaseEngine,
                CallEffect.CreateAnswer("sdp", false),
                CallEffect.StartTimer(CallTimer.Connect, c),
            ),
            t.effects,
        )
    }

    @Test
    fun userAcceptInIncomingAnswers() {
        val t = machine.step(incoming, CallEvent.UserAccept)
        assertEquals(CallPhase.Connecting, t.state.phase)
        assertEquals(null, t.state.pendingOfferSdp)
        assertEquals(
            listOf(CallEffect.CancelTimers, CallEffect.CreateAnswer("sdp", false), CallEffect.StartTimer(CallTimer.Connect, c)),
            t.effects,
        )
    }

    @Test
    fun userAcceptOutsideIncomingIsIgnored() {
        listOf(idle, ended, outgoing, connecting, connected, reconnecting).forEach { assertNoOp(it, CallEvent.UserAccept) }
    }

    @Test
    fun localHangupInEveryPhase() {
        assertEnds(machine.step(outgoing, CallEvent.UserHangup), CallEndReason.Cancelled, HangupReason.Cancelled)
        assertEnds(machine.step(incoming, CallEvent.UserDecline), CallEndReason.Declined, HangupReason.Declined)
        assertEnds(machine.step(incoming, CallEvent.UserHangup), CallEndReason.Declined, HangupReason.Declined)
        listOf(connecting, connected, reconnecting).forEach {
            assertEnds(machine.step(it, CallEvent.UserHangup), CallEndReason.Hangup(remote = false), HangupReason.Normal)
        }
        listOf(idle, ended).forEach {
            assertNoOp(it, CallEvent.UserHangup)
            assertNoOp(it, CallEvent.UserDecline)
        }
    }

    @Test
    fun remoteHangupInEveryPhase() {
        fun hang(reason: HangupReason = HangupReason.Normal) = fromSam(CallSignal.Hangup(c, reason))
        assertEnds(machine.step(outgoing, hang(HangupReason.Declined)), CallEndReason.Declined, null)
        assertEnds(machine.step(outgoing, hang(HangupReason.Busy)), CallEndReason.Busy, null)
        assertEnds(machine.step(incoming, hang(HangupReason.Cancelled)), CallEndReason.Cancelled, null)
        assertEnds(machine.step(incoming, hang(HangupReason.Missed)), CallEndReason.Missed, null)
        listOf(connecting, connected, reconnecting).forEach {
            assertEnds(machine.step(it, hang()), CallEndReason.Hangup(remote = true), null)
        }
    }

    @Test
    fun answerFromOwnIdentityIsIgnored() {
        listOf(incoming, outgoing, connecting, connected, reconnecting).forEach {
            assertNoOp(it, signal(frodo, CallSignal.Answer(c, "x")))
        }
    }

    @Test
    fun answerFromPeerMovesOutgoingToConnecting() {
        val t = machine.step(outgoing, fromSam(CallSignal.Answer(c, "ans")))
        assertEquals(CallPhase.Connecting, t.state.phase)
        assertEquals(
            listOf(CallEffect.CancelTimers, CallEffect.ApplyRemoteAnswer("ans"), CallEffect.StartTimer(CallTimer.Connect, c)),
            t.effects,
        )
        listOf(incoming, connecting, connected, reconnecting).forEach { assertNoOp(it, fromSam(CallSignal.Answer(c, "ans"))) }
    }

    @Test
    fun ringMarksPeerRingingOnlyWhileOutgoing() {
        assertEquals(true, machine.step(outgoing, fromSam(CallSignal.Ringing(c))).state.peerRinging)
        listOf(incoming, connecting, connected, reconnecting).forEach { assertNoOp(it, fromSam(CallSignal.Ringing(c))) }
    }

    @Test
    fun remoteIceBecomesAddIceEffect() {
        active.values.forEach {
            val t = machine.step(it, fromSam(CallSignal.Ice(c, "cand", "0", 1)))
            assertEquals(it, t.state)
            assertEquals(listOf(CallEffect.AddIce("cand", "0", 1)), t.effects)
        }
    }

    @Test
    fun signalsForAForeignCallOrSenderOrWhileInactiveAreIgnored() {
        val hang = CallSignal.Hangup(c)
        active.values.forEach {
            assertNoOp(it, fromSam(CallSignal.Hangup("other", HangupReason.Normal)))
            assertNoOp(it, signal("gimli.demo.rocks", hang))
            assertNoOp(it, signal(frodo, hang))
            assertNoOp(it, signal("gimli.demo.rocks", CallSignal.Ice(c, "x")))
        }
        listOf(idle, ended).forEach {
            assertNoOp(it, fromSam(hang))
            assertNoOp(it, fromSam(CallSignal.Ice(c, "x")))
            assertNoOp(it, fromSam(CallSignal.Answer(c, "x")))
        }
    }

    @Test
    fun ringTimerEndsMissedWithTheCorrectNotification() {
        assertEnds(machine.step(outgoing, CallEvent.TimerFired(CallTimer.Ring, c)), CallEndReason.Missed, HangupReason.Missed)
        assertEnds(machine.step(incoming, CallEvent.TimerFired(CallTimer.Ring, c)), CallEndReason.Missed, null)
        listOf(connecting, connected, reconnecting, idle, ended).forEach {
            assertNoOp(it, CallEvent.TimerFired(CallTimer.Ring, c))
        }
    }

    @Test
    fun connectTimerFailsOnlyWhileConnecting() {
        assertEnds(
            machine.step(connecting, CallEvent.TimerFired(CallTimer.Connect, c)),
            CallEndReason.Failed(FailReason.NoRoute),
            HangupReason.Normal,
        )
        listOf(outgoing, incoming, connected, reconnecting, idle).forEach {
            assertNoOp(it, CallEvent.TimerFired(CallTimer.Connect, c))
        }
    }

    @Test
    fun reconnectTicksCountUpThenFail() {
        var s = reconnecting
        assertEquals(1, s.reconnectAttempt)
        repeat(MAX_RECONNECT_ATTEMPTS - 1) {
            val t = machine.step(s, CallEvent.TimerFired(CallTimer.Reconnect, c))
            assertEquals(listOf(CallEffect.RestartIce), t.effects)
            s = t.state
        }
        assertEquals(MAX_RECONNECT_ATTEMPTS, s.reconnectAttempt)
        assertEnds(
            machine.step(s, CallEvent.TimerFired(CallTimer.Reconnect, c)),
            CallEndReason.Failed(FailReason.NoRoute),
            HangupReason.Normal,
        )
        listOf(connected, connecting).forEach { assertNoOp(it, CallEvent.TimerFired(CallTimer.Reconnect, c)) }
    }

    @Test
    fun staleTimerForAnotherCallIsIgnored() {
        active.values.forEach { s -> CallTimer.entries.forEach { assertNoOp(s, CallEvent.TimerFired(it, "old")) } }
    }

    @Test
    fun mediaConnectedTransitions() {
        val t = machine.step(connecting, CallEvent.Media(MediaEvent.Connected), now = 7_000)
        assertEquals(CallPhase.Connected, t.state.phase)
        assertEquals(7_000L, t.state.connectedAtMs)
        assertEquals(listOf(CallEffect.CancelTimers), t.effects)

        val r = machine.step(reconnecting, CallEvent.Media(MediaEvent.Connected))
        assertEquals(CallPhase.Connected, r.state.phase)
        assertEquals(0, r.state.reconnectAttempt)
        assertEquals(5_000L, r.state.connectedAtMs)
        assertEquals(listOf(CallEffect.CancelTimers), r.effects)

        listOf(idle, ended, outgoing, incoming, connected).forEach { assertNoOp(it, CallEvent.Media(MediaEvent.Connected)) }
    }

    @Test
    fun mediaDisconnectedOnlyFromConnected() {
        val t = machine.step(connected, CallEvent.Media(MediaEvent.Disconnected))
        assertEquals(CallPhase.Reconnecting, t.state.phase)
        assertEquals(1, t.state.reconnectAttempt)
        assertEquals(listOf(CallEffect.StartTimer(CallTimer.Reconnect, c), CallEffect.RestartIce), t.effects)
        listOf(idle, outgoing, incoming, connecting, reconnecting).forEach {
            assertNoOp(it, CallEvent.Media(MediaEvent.Disconnected))
        }
    }

    @Test
    fun mediaFailureAndEngineErrorEndActiveCalls() {
        active.forEach { (_, s) ->
            assertEnds(
                machine.step(s, CallEvent.Media(MediaEvent.Failed(FailReason.Other))),
                CallEndReason.Failed(FailReason.Other),
                HangupReason.Normal,
            )
            assertEnds(
                machine.step(s, CallEvent.EngineError(FailReason.Permission)),
                CallEndReason.Failed(FailReason.Permission),
                HangupReason.Normal,
            )
        }
        listOf(idle, ended).forEach {
            assertNoOp(it, CallEvent.Media(MediaEvent.Failed(FailReason.Other)))
            assertNoOp(it, CallEvent.EngineError(FailReason.Other))
        }
    }

    @Test
    fun remoteVideoAndQualityUpdateState() {
        val v = machine.step(connected, CallEvent.Media(MediaEvent.RemoteVideoChanged(true)))
        assertEquals(true, v.state.media.remoteVideo)
        assertEquals(emptyList(), v.effects)
        val q = machine.step(connected, CallEvent.Media(MediaEvent.QualityChanged(CallQuality.Poor)))
        assertEquals(CallQuality.Poor, q.state.quality)
    }

    @Test
    fun localIceIsSentExceptWhileIncoming() {
        val ice = CallEvent.Media(MediaEvent.LocalIce("cand", "0", 1))
        listOf(outgoing, connecting, connected, reconnecting).forEach {
            val t = machine.step(it, ice)
            assertEquals(it, t.state)
            assertEquals(listOf(CallEffect.SendSignal(sam, CallSignal.Ice(c, "cand", "0", 1))), t.effects)
        }
        assertNoOp(incoming, ice)
    }

    @Test
    fun controlsApplyWhileTheCallIsLiveAndAreIgnoredOtherwise() {
        listOf(outgoing, connecting, connected, reconnecting).forEach {
            machine.step(it, CallEvent.SetMuted(true)).let { t ->
                assertEquals(true, t.state.media.muted)
                assertEquals(listOf(CallEffect.SetMuted(true)), t.effects)
            }
            machine.step(it, CallEvent.SetVideo(true)).let { t ->
                assertEquals(true, t.state.media.localVideo)
                assertEquals(listOf(CallEffect.SetVideo(true)), t.effects)
            }
            machine.step(it, CallEvent.SwitchCamera).let { t ->
                assertEquals(CameraFacing.Back, t.state.media.camera)
                assertEquals(listOf(CallEffect.SwitchCamera), t.effects)
            }
            machine.step(it, CallEvent.SetRoute(AudioRoute.Speaker)).let { t ->
                assertEquals(AudioRoute.Speaker, t.state.media.route)
                assertEquals(listOf(CallEffect.SetRoute(AudioRoute.Speaker)), t.effects)
            }
        }
        listOf(idle, incoming, ended).forEach {
            assertNoOp(it, CallEvent.SetMuted(true))
            assertNoOp(it, CallEvent.SetVideo(true))
            assertNoOp(it, CallEvent.SwitchCamera)
            assertNoOp(it, CallEvent.SetRoute(AudioRoute.Speaker))
        }
    }

    @Test
    fun switchCameraTogglesBackToFront() {
        val back = machine.step(connected, CallEvent.SwitchCamera).state
        assertEquals(CameraFacing.Front, machine.step(back, CallEvent.SwitchCamera).state.media.camera)
        assertNotNull(back.callId)
    }
}
