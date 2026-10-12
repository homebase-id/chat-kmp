package id.homebase.chat.services.calling

enum class CallTimer { Ring, Connect, Reconnect }

sealed interface CallEvent {
    data class UserCall(val callId: String, val peer: String, val video: Boolean) : CallEvent
    data object UserAccept : CallEvent
    data object UserDecline : CallEvent
    data object UserHangup : CallEvent
    data class Signal(val inbound: InboundSignal) : CallEvent
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
    data class AddIce(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?) : CallEffect
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

    fun reduce(state: CallState, event: CallEvent, nowMs: Long): CallTransition = when (event) {
        is CallEvent.UserCall -> userCall(state, event)
        CallEvent.UserAccept -> userAccept(state)
        CallEvent.UserDecline, CallEvent.UserHangup -> userHangup(state)
        is CallEvent.Signal -> signal(state, event.inbound, nowMs)
        is CallEvent.TimerFired -> timer(state, event)
        is CallEvent.Media -> media(state, event.event, nowMs)
        is CallEvent.EngineError -> failed(state, event.reason)
        is CallEvent.SetMuted -> control(state, CallEffect.SetMuted(event.muted)) { it.copy(muted = event.muted) }
        is CallEvent.SetVideo -> control(state, CallEffect.SetVideo(event.on)) { it.copy(localVideo = event.on) }
        CallEvent.SwitchCamera -> control(state, CallEffect.SwitchCamera) {
            it.copy(camera = if (it.camera == CameraFacing.Front) CameraFacing.Back else CameraFacing.Front)
        }
        is CallEvent.SetRoute -> control(state, CallEffect.SetRoute(event.route)) { it.copy(route = event.route) }
    }

    private fun CallState.canStart() = phase is CallPhase.Idle || phase is CallPhase.Ended

    private fun userCall(s: CallState, e: CallEvent.UserCall): CallTransition {
        if (!s.canStart() || e.peer == selfOdinId) return CallTransition(s)
        val next = CallState(
            phase = CallPhase.Outgoing,
            callId = e.callId,
            peer = e.peer,
            direction = CallDirection.Outgoing,
            media = CallMedia(localVideo = e.video),
        )
        return CallTransition(
            next,
            listOf(CallEffect.CreateOffer(e.video), CallEffect.StartTimer(CallTimer.Ring, e.callId)),
        )
    }

    private fun userAccept(s: CallState): CallTransition {
        if (s.phase != CallPhase.Incoming) return CallTransition(s)
        val offer = s.pendingOfferSdp ?: return CallTransition(s)
        return CallTransition(
            s.copy(phase = CallPhase.Connecting, pendingOfferSdp = null),
            listOf(
                CallEffect.CancelTimers,
                CallEffect.CreateAnswer(offer, s.media.localVideo),
                CallEffect.StartTimer(CallTimer.Connect, s.callId!!),
            ),
        )
    }

    private fun userHangup(s: CallState): CallTransition = when (s.phase) {
        CallPhase.Outgoing -> end(s, CallEndReason.Cancelled, HangupReason.Cancelled)
        CallPhase.Incoming -> end(s, CallEndReason.Declined, HangupReason.Declined)
        CallPhase.Connecting, CallPhase.Connected, CallPhase.Reconnecting ->
            end(s, CallEndReason.Hangup(remote = false), HangupReason.Normal)
        else -> CallTransition(s)
    }

    private fun signal(s: CallState, inbound: InboundSignal, nowMs: Long): CallTransition {
        val sig = inbound.signal
        if (sig is CallSignal.Offer) return offer(s, inbound.from, sig, inbound.receivedAtMs, nowMs)
        if (!s.isActive || sig.callId != s.callId) return CallTransition(s)
        if (inbound.from == selfOdinId) {
            return if (s.phase == CallPhase.Incoming && sig is CallSignal.Answer) {
                end(s, CallEndReason.AnsweredElsewhere)
            } else {
                CallTransition(s)
            }
        }
        if (inbound.from != s.peer) return CallTransition(s)
        return when (sig) {
            is CallSignal.Offer -> CallTransition(s)
            is CallSignal.Ring ->
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

    private fun offer(s: CallState, from: String, sig: CallSignal.Offer, receivedAtMs: Long, nowMs: Long): CallTransition {
        if (from == selfOdinId || sig.callId == s.callId) return CallTransition(s)
        if (nowMs - receivedAtMs > RING_TIMEOUT_MS) return CallTransition(s)
        if (s.canStart()) {
            val next = CallState(
                phase = CallPhase.Incoming,
                callId = sig.callId,
                peer = from,
                direction = CallDirection.Incoming,
                media = CallMedia(localVideo = sig.video, remoteVideo = sig.video),
                pendingOfferSdp = sig.sdp,
            )
            return CallTransition(
                next,
                listOf(
                    CallEffect.SendSignal(from, CallSignal.Ring(sig.callId)),
                    CallEffect.StartTimer(CallTimer.Ring, sig.callId),
                ),
            )
        }
        val glare = s.phase == CallPhase.Outgoing && from == s.peer
        if (glare) {
            if (selfOdinId < from) return CallTransition(s)
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
                    CallEffect.SendSignal(from, CallSignal.Hangup(s.callId!!, HangupReason.Cancelled)),
                    CallEffect.ReleaseEngine,
                    CallEffect.CreateAnswer(sig.sdp, s.media.localVideo),
                    CallEffect.StartTimer(CallTimer.Connect, sig.callId),
                ),
            )
        }
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
                        CallTransition(s.copy(reconnectAttempt = attempt))
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
                    s.copy(phase = CallPhase.Connected, connectedAtMs = nowMs),
                    listOf(CallEffect.CancelTimers),
                )
                CallPhase.Reconnecting -> CallTransition(
                    s.copy(phase = CallPhase.Connected, reconnectAttempt = 0),
                    listOf(CallEffect.CancelTimers),
                )
                else -> CallTransition(s)
            }
            MediaEvent.Disconnected ->
                if (s.phase == CallPhase.Connected) {
                    CallTransition(
                        s.copy(phase = CallPhase.Reconnecting, reconnectAttempt = 1),
                        listOf(CallEffect.StartTimer(CallTimer.Reconnect, s.callId!!)),
                    )
                } else {
                    CallTransition(s)
                }
            is MediaEvent.Failed -> failed(s, e.reason)
            is MediaEvent.RemoteVideoChanged -> CallTransition(s.copy(media = s.media.copy(remoteVideo = e.on)))
            is MediaEvent.QualityChanged -> CallTransition(s.copy(quality = e.quality))
            is MediaEvent.LocalIce ->
                if (s.phase == CallPhase.Incoming) {
                    CallTransition(s)
                } else {
                    CallTransition(
                        s,
                        listOf(CallEffect.SendSignal(s.peer!!, CallSignal.Ice(s.callId!!, e.candidate, e.sdpMid, e.sdpMLineIndex))),
                    )
                }
        }
    }

    private fun failed(s: CallState, reason: FailReason): CallTransition =
        if (s.isActive) end(s, CallEndReason.Failed(reason), HangupReason.Normal) else CallTransition(s)

    private fun control(s: CallState, effect: CallEffect, update: (CallMedia) -> CallMedia): CallTransition =
        when (s.phase) {
            CallPhase.Outgoing, CallPhase.Connecting, CallPhase.Connected, CallPhase.Reconnecting ->
                CallTransition(s.copy(media = update(s.media)), listOf(effect))
            else -> CallTransition(s)
        }

    private fun end(s: CallState, reason: CallEndReason, notify: HangupReason? = null): CallTransition {
        val effects = buildList {
            add(CallEffect.CancelTimers)
            if (notify != null && s.peer != null && s.callId != null) {
                add(CallEffect.SendSignal(s.peer, CallSignal.Hangup(s.callId, notify)))
            }
            add(CallEffect.ReleaseEngine)
        }
        return CallTransition(
            s.copy(phase = CallPhase.Ended(reason), pendingOfferSdp = null, peerRinging = false),
            effects,
        )
    }
}
