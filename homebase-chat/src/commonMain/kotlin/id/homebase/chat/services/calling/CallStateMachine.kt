package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger

enum class CallTimer { Ring, Connect, Reconnect }

sealed interface CallEvent {
    data class UserCall(val callId: String, val peer: String, val video: Boolean) : CallEvent
    data object UserAccept : CallEvent
    data object UserDecline : CallEvent
    data object UserHangup : CallEvent
    data class Signal(val inbound: InboundSignal) : CallEvent
    /** A `ring` push arrived before (or without) the socket offer; collapses with the offer by [callId]. */
    data class PushRing(val callId: String, val from: String) : CallEvent
    data class PushHangup(val callId: String) : CallEvent
    data class Verification(val callId: String, val code: String) : CallEvent
    data class TimerFired(val timer: CallTimer, val callId: String) : CallEvent
    data class Media(val event: MediaEvent) : CallEvent
    data class EngineError(val reason: FailReason) : CallEvent
    data class SetMuted(val muted: Boolean) : CallEvent
    data class SetVideo(val on: Boolean) : CallEvent
    data object SwitchCamera : CallEvent
    data class SetRoute(val route: AudioRoute) : CallEvent
}

sealed interface CallEffect {
    data class SendSignal(val peer: String, val signal: CallSignal) : CallEffect
    data class CreateOffer(val video: Boolean) : CallEffect
    data class CreateAnswer(val offerSdp: String, val video: Boolean) : CallEffect
    data class ApplyRemoteAnswer(val sdp: String) : CallEffect
    data object RestartIce : CallEffect
    data class AnswerRestart(val offerSdp: String) : CallEffect
    data class AddIce(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?) : CallEffect
    /** Caller-side candidate gathered before any device answered: folded into the retained offer, not trickled. */
    data class HoldIce(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?) : CallEffect
    data class SetMuted(val muted: Boolean) : CallEffect
    data class SetVideo(val on: Boolean) : CallEffect
    data object SwitchCamera : CallEffect
    data class SetRoute(val route: AudioRoute) : CallEffect
    data class StartTimer(val timer: CallTimer, val callId: String) : CallEffect
    data object CancelTimers : CallEffect
    data object ReleaseEngine : CallEffect
}

data class CallTransition(val state: CallState, val effects: List<CallEffect> = emptyList())

class CallStateMachine(private val selfOdinId: String) {

    fun reduce(state: CallState, event: CallEvent, nowMs: Long): CallTransition =
        decide(state, event, nowMs).also { log(state, event, it) }

    private fun log(before: CallState, event: CallEvent, t: CallTransition) {
        val after = t.state
        if (before.phase == after.phase && before.callId == after.callId) return
        val hangup = t.effects.filterIsInstance<CallEffect.SendSignal>().map { it.signal }.filterIsInstance<CallSignal.Hangup>().firstOrNull()
        Logger.i(TAG) {
            "call=${after.callId} ${before.phase} -> ${after.phase} on ${describe(event)}" +
                (hangup?.let { " sends hangup(${it.reason})" } ?: "")
        }
    }

    private fun describe(event: CallEvent): String = when (event) {
        is CallEvent.Signal -> "signal ${event.inbound.signal::class.simpleName} from ${event.inbound.from}"
        is CallEvent.TimerFired -> "timer ${event.timer}"
        is CallEvent.Media -> "media ${event.event::class.simpleName}"
        is CallEvent.EngineError -> "engine error ${event.reason}"
        else -> event::class.simpleName.orEmpty()
    }

    private fun decide(state: CallState, event: CallEvent, nowMs: Long): CallTransition = when (event) {
        is CallEvent.UserCall -> userCall(state, event)
        CallEvent.UserAccept -> userAccept(state)
        CallEvent.UserDecline, CallEvent.UserHangup -> userHangup(state)
        is CallEvent.Signal -> signal(state, event.inbound, nowMs)
        is CallEvent.PushRing -> pushRing(state, event)
        is CallEvent.PushHangup ->
            if (state.phase == CallPhase.Incoming && state.callId == event.callId) end(state, CallEndReason.Cancelled)
            else CallTransition(state)
        is CallEvent.Verification ->
            if (state.isActive && state.callId == event.callId) CallTransition(state.copy(verificationCode = event.code))
            else CallTransition(state)
        is CallEvent.TimerFired -> timer(state, event)
        is CallEvent.Media -> media(state, event.event, nowMs)
        is CallEvent.EngineError -> failed(state, event.reason)
        is CallEvent.SetMuted -> control(state, CallEffect.SetMuted(event.muted)) { it.copy(muted = event.muted) }
        is CallEvent.SetVideo ->
            if (state.phase == CallPhase.Incoming && !event.on) CallTransition(state.copy(media = state.media.copy(localVideo = false)))
            else control(state, CallEffect.SetVideo(event.on)) { it.copy(localVideo = event.on) }
        CallEvent.SwitchCamera -> control(state, CallEffect.SwitchCamera) {
            it.copy(camera = if (it.camera == CameraFacing.Front) CameraFacing.Back else CameraFacing.Front)
        }
        is CallEvent.SetRoute -> control(state, CallEffect.SetRoute(event.route)) { it.copy(route = event.route) }
    }

    private fun CallPhase.isEstablishing() =
        this == CallPhase.Connecting || this == CallPhase.Connected || this == CallPhase.Reconnecting

    private fun CallState.canStart() = phase is CallPhase.Idle || phase is CallPhase.Ended

    private fun userCall(s: CallState, e: CallEvent.UserCall): CallTransition {
        if (!s.canStart() || e.peer == selfOdinId) return CallTransition(s)
        val next = CallState(
            phase = CallPhase.Outgoing,
            callId = e.callId,
            peer = e.peer,
            direction = CallDirection.Outgoing,
            media = CallMedia(localVideo = e.video),
            placedWithVideo = e.video,
        )
        return CallTransition(
            next,
            listOf(CallEffect.CreateOffer(e.video), CallEffect.StartTimer(CallTimer.Ring, e.callId)),
        )
    }

    private fun userAccept(s: CallState): CallTransition {
        if (s.phase != CallPhase.Incoming) return CallTransition(s)
        val offer = s.pendingOfferSdp ?: return CallTransition(s.copy(acceptPending = true))
        val elsewhere = answeredElsewhere(s.callId!!)
        return CallTransition(
            s.copy(phase = CallPhase.Connecting, pendingOfferSdp = null),
            listOf(
                CallEffect.CancelTimers,
                elsewhere,
                CallEffect.CreateAnswer(offer, s.media.localVideo),
                CallEffect.StartTimer(CallTimer.Connect, s.callId!!),
            ),
        )
    }

    private fun answeredElsewhere(callId: String) =
        CallEffect.SendSignal(selfOdinId, CallSignal.Hangup(callId, HangupReason.AnsweredElsewhere))

    private fun userHangup(s: CallState): CallTransition = when (s.phase) {
        CallPhase.Outgoing -> end(s, CallEndReason.Cancelled, HangupReason.Cancelled)
        CallPhase.Incoming -> end(s, CallEndReason.Declined, HangupReason.Declined, HangupReason.DeclinedElsewhere)
        CallPhase.Connecting, CallPhase.Connected, CallPhase.Reconnecting ->
            end(s, CallEndReason.Hangup(remote = false), HangupReason.Normal)
        else -> CallTransition(s)
    }

    private fun signal(s: CallState, inbound: InboundSignal, nowMs: Long): CallTransition {
        val sig = inbound.signal
        if (sig is CallSignal.Offer) return offer(s, inbound.from, sig, inbound.receivedAtMs, nowMs)
        if (sig is CallSignal.Hangup && sig.reason.isElsewhere) return elsewhere(s, inbound.from, sig)
        if (!s.isActive || sig.callId != s.callId) return CallTransition(s)
        if (inbound.from != s.peer) return CallTransition(s)
        return when (sig) {
            is CallSignal.Offer -> CallTransition(s)
            is CallSignal.Ringing ->
                if (s.phase == CallPhase.Outgoing) CallTransition(s.copy(peerRinging = true)) else CallTransition(s)
            is CallSignal.Answer ->
                if (s.phase == CallPhase.Outgoing) {
                    CallTransition(
                        s.copy(phase = CallPhase.Connecting, peerRinging = false),
                        listOf(
                            CallEffect.CancelTimers,
                            CallEffect.ApplyRemoteAnswer(sig.sdp),
                            CallEffect.StartTimer(CallTimer.Connect, s.callId!!),
                        ),
                    )
                } else {
                    CallTransition(s)
                }
            is CallSignal.Restart ->
                if (s.phase.isEstablishing()) CallTransition(s, listOf(CallEffect.AnswerRestart(sig.sdp))) else CallTransition(s)
            is CallSignal.RestartAnswer -> restartAnswer(s, sig.sdp)
            is CallSignal.Ice -> CallTransition(s, listOf(CallEffect.AddIce(sig.candidate, sig.sdpMid, sig.sdpMLineIndex)))
            is CallSignal.Hangup -> when (s.phase) {
                CallPhase.Outgoing -> end(
                    s,
                    if (sig.reason == HangupReason.Busy) CallEndReason.Busy else CallEndReason.Declined,
                )
                CallPhase.Incoming -> end(
                    s,
                    if (sig.reason == HangupReason.Missed) CallEndReason.Missed else CallEndReason.Cancelled,
                )
                else -> end(s, CallEndReason.Hangup(remote = true))
            }
        }
    }

    // Our own echo arrives while we are Connecting/Ended, so only a sibling device still ringing for this call ends here.
    private fun elsewhere(s: CallState, from: String, sig: CallSignal.Hangup): CallTransition {
        if (from != selfOdinId || s.phase != CallPhase.Incoming || s.callId != sig.callId) return CallTransition(s)
        return end(s, if (sig.reason == HangupReason.AnsweredElsewhere) CallEndReason.AnsweredElsewhere else CallEndReason.DeclinedElsewhere)
    }

    private fun offer(s: CallState, from: String, sig: CallSignal.Offer, receivedAtMs: Long, nowMs: Long): CallTransition {
        if (from == selfOdinId) return CallTransition(s)
        if (nowMs - receivedAtMs > RING_TIMEOUT_MS) {
            Logger.i(TAG) { "stale offer call=${sig.callId} age=${nowMs - receivedAtMs}ms dropped" }
            return CallTransition(s)
        }
        if (sig.callId == s.callId) return offerForPushedRing(s, from, sig)
        if (s.canStart()) {
            val next = CallState(
                phase = CallPhase.Incoming,
                callId = sig.callId,
                peer = from,
                direction = CallDirection.Incoming,
                media = CallMedia(localVideo = sig.video, remoteVideo = sig.video),
                placedWithVideo = sig.video,
                pendingOfferSdp = sig.sdp,
                offerSeq = sig.seq,
            )
            return CallTransition(
                next,
                listOf(
                    CallEffect.SendSignal(from, CallSignal.Ringing(sig.callId)),
                    CallEffect.StartTimer(CallTimer.Ring, sig.callId),
                ),
            )
        }
        val glare = s.phase == CallPhase.Outgoing && from == s.peer
        if (glare) {
            if (selfOdinId < from) {
                Logger.i(TAG) { "glare: keeping own call=${s.callId}, ignoring peer offer call=${sig.callId}" }
                return CallTransition(s)
            }
            Logger.i(TAG) { "glare: yielding own call=${s.callId} to peer offer call=${sig.callId}" }
            val next = s.copy(
                phase = CallPhase.Connecting,
                callId = sig.callId,
                direction = CallDirection.Incoming,
                media = s.media.copy(remoteVideo = sig.video),
                peerRinging = false,
            )
            return CallTransition(
                next,
                listOf(
                    CallEffect.CancelTimers,
                    CallEffect.SendSignal(from, CallSignal.Hangup(s.callId!!, HangupReason.Normal)),
                    CallEffect.ReleaseEngine,
                    CallEffect.CreateAnswer(sig.sdp, s.media.localVideo),
                    CallEffect.StartTimer(CallTimer.Connect, sig.callId),
                ),
            )
        }
        Logger.i(TAG) { "busy: refusing offer call=${sig.callId} from=$from while ${s.phase} call=${s.callId}" }
        return CallTransition(s, listOf(CallEffect.SendSignal(from, CallSignal.Hangup(sig.callId, HangupReason.Busy))))
    }

    private fun timer(s: CallState, e: CallEvent.TimerFired): CallTransition {
        if (!s.isActive || e.callId != s.callId) return CallTransition(s)
        return when (e.timer) {
            CallTimer.Ring -> when (s.phase) {
                CallPhase.Outgoing -> end(s, CallEndReason.Missed, HangupReason.Missed)
                CallPhase.Incoming -> end(s, CallEndReason.Missed)
                else -> CallTransition(s)
            }
            CallTimer.Connect ->
                if (s.phase == CallPhase.Connecting) {
                    end(s, CallEndReason.Failed(FailReason.NoRoute), HangupReason.Normal)
                } else {
                    CallTransition(s)
                }
            CallTimer.Reconnect ->
                if (s.phase == CallPhase.Reconnecting) {
                    val attempt = s.reconnectAttempt + 1
                    if (attempt > MAX_RECONNECT_ATTEMPTS) {
                        end(s, CallEndReason.Failed(FailReason.NoRoute), HangupReason.Normal)
                    } else {
                        CallTransition(s.copy(reconnectAttempt = attempt).restarting(), restartIce(s))
                    }
                } else {
                    CallTransition(s)
                }
        }
    }

    private fun media(s: CallState, e: MediaEvent, nowMs: Long): CallTransition {
        if (!s.isActive) return CallTransition(s)
        return when (e) {
            MediaEvent.Connected -> when (s.phase) {
                CallPhase.Connecting -> CallTransition(
                    s.copy(phase = CallPhase.Connected, connectedAtMs = nowMs, linkUp = true),
                    listOf(CallEffect.CancelTimers),
                )
                CallPhase.Reconnecting ->
                    if (s.restartPending) {
                        Logger.i(TAG) { "call=${s.callId} link up while the ICE restart is unanswered; still reconnecting" }
                        CallTransition(s.copy(linkUp = true))
                    } else {
                        reconnected(s)
                    }
                else -> CallTransition(s)
            }
            MediaEvent.Disconnected ->
                if (s.phase == CallPhase.Connected) {
                    CallTransition(
                        s.copy(phase = CallPhase.Reconnecting, reconnectAttempt = 1, linkUp = false).restarting(),
                        listOf(CallEffect.StartTimer(CallTimer.Reconnect, s.callId!!)) + restartIce(s),
                    )
                } else if (s.phase == CallPhase.Reconnecting) {
                    CallTransition(s.copy(linkUp = false))
                } else {
                    CallTransition(s)
                }
            is MediaEvent.Failed -> failed(s, e.reason)
            is MediaEvent.RemoteVideoChanged -> CallTransition(s.copy(media = s.media.copy(remoteVideo = e.on)))
            is MediaEvent.QualityChanged -> CallTransition(s.copy(quality = e.quality))
            is MediaEvent.RouteChanged -> CallTransition(s.copy(media = s.media.copy(route = e.route)))
            is MediaEvent.LocalIce -> when (s.phase) {
                CallPhase.Incoming -> CallTransition(s)
                CallPhase.Outgoing -> CallTransition(s, listOf(CallEffect.HoldIce(e.candidate, e.sdpMid, e.sdpMLineIndex)))
                else -> CallTransition(
                    s,
                    listOf(CallEffect.SendSignal(s.peer!!, CallSignal.Ice(s.callId!!, e.candidate, e.sdpMid, e.sdpMLineIndex))),
                )
            }
        }
    }

    // Only the original caller drives the renegotiation, so the two sides never offer at once.
    private fun restartIce(s: CallState): List<CallEffect> =
        if (s.direction == CallDirection.Outgoing) listOf(CallEffect.RestartIce) else emptyList()

    // A restart offer set locally but never answered leaves the caller on ICE credentials the peer
    // has not seen: the caller's link can report up while the peer's checks are rejected.
    private fun CallState.restarting() = if (direction == CallDirection.Outgoing) copy(restartPending = true) else this

    private fun reconnected(s: CallState) =
        CallTransition(s.copy(phase = CallPhase.Connected, reconnectAttempt = 0, linkUp = true), listOf(CallEffect.CancelTimers))

    private fun restartAnswer(s: CallState, sdp: String): CallTransition {
        if (!s.phase.isEstablishing() || !s.restartPending) return CallTransition(s)
        val answered = s.copy(restartPending = false)
        val apply = CallEffect.ApplyRemoteAnswer(sdp)
        if (answered.phase != CallPhase.Reconnecting || !answered.linkUp) return CallTransition(answered, listOf(apply))
        return CallTransition(
            answered.copy(phase = CallPhase.Connected, reconnectAttempt = 0, linkUp = true),
            listOf(apply, CallEffect.CancelTimers),
        )
    }

    private fun pushRing(s: CallState, e: CallEvent.PushRing): CallTransition {
        if (e.from == selfOdinId || e.callId == s.callId || !s.canStart()) return CallTransition(s)
        return CallTransition(
            CallState(phase = CallPhase.Incoming, callId = e.callId, peer = e.from, direction = CallDirection.Incoming),
            listOf(CallEffect.StartTimer(CallTimer.Ring, e.callId)),
        )
    }

    private fun offerForPushedRing(s: CallState, from: String, sig: CallSignal.Offer): CallTransition {
        if (s.phase != CallPhase.Incoming || s.peer != from) return CallTransition(s)
        if (s.pendingOfferSdp != null) {
            // A re-published offer of the same call carries more candidates; only a newer seq replaces the one we hold.
            if (sig.seq <= s.offerSeq) return CallTransition(s)
            return CallTransition(s.copy(pendingOfferSdp = sig.sdp, offerSeq = sig.seq))
        }
        val ringing = CallEffect.SendSignal(from, CallSignal.Ringing(sig.callId))
        val media = s.media.copy(localVideo = sig.video && !s.acceptPending, remoteVideo = sig.video)
        if (!s.acceptPending) return CallTransition(s.copy(pendingOfferSdp = sig.sdp, offerSeq = sig.seq, media = media), listOf(ringing))
        return CallTransition(
            s.copy(phase = CallPhase.Connecting, acceptPending = false, media = media, offerSeq = sig.seq),
            listOf(
                ringing,
                CallEffect.CancelTimers,
                answeredElsewhere(sig.callId),
                CallEffect.CreateAnswer(sig.sdp, sig.video),
                CallEffect.StartTimer(CallTimer.Connect, sig.callId),
            ),
        )
    }

    private fun failed(s: CallState, reason: FailReason): CallTransition =
        if (s.isActive) end(s, CallEndReason.Failed(reason), HangupReason.Normal) else CallTransition(s)

    private fun control(s: CallState, effect: CallEffect, update: (CallMedia) -> CallMedia): CallTransition =
        when (s.phase) {
            CallPhase.Outgoing, CallPhase.Connecting, CallPhase.Connected, CallPhase.Reconnecting ->
                CallTransition(s.copy(media = update(s.media)), listOf(effect))
            else -> CallTransition(s)
        }

    private fun end(s: CallState, reason: CallEndReason, notify: HangupReason? = null, notifySelf: HangupReason? = null): CallTransition {
        val effects = buildList {
            add(CallEffect.CancelTimers)
            if (notify != null && s.peer != null && s.callId != null) {
                add(CallEffect.SendSignal(s.peer, CallSignal.Hangup(s.callId, notify)))
            }
            if (notifySelf != null && s.callId != null) {
                add(CallEffect.SendSignal(selfOdinId, CallSignal.Hangup(s.callId, notifySelf)))
            }
            add(CallEffect.ReleaseEngine)
        }
        return CallTransition(
            s.copy(phase = CallPhase.Ended(reason), pendingOfferSdp = null, acceptPending = false, peerRinging = false),
            effects,
        )
    }
}

private const val TAG = "CallStateMachine"
