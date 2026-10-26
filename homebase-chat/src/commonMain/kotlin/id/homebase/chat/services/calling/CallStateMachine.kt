package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger

enum class CallTimer { Ring, Connect, Reconnect }

sealed interface CallEvent {
    data class UserCall(val callId: String, val peer: String, val video: Boolean) : CallEvent
    data object UserAccept : CallEvent
    data object NoLocalVideoTrack : CallEvent
    data object UserDecline : CallEvent
    data object UserHangup : CallEvent
    data class Signal(val inbound: InboundSignal) : CallEvent
    /** A `ring` push arrived before (or without) the socket offer; collapses with the offer by [callId]. */
    data class PushRing(val callId: String, val from: String, val sentAtMs: Long = 0) : CallEvent
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
    /** The first camera of a call that began without one goes onto the idle video transceiver; nothing is renegotiated. */
    data object AddVideo : CallEffect
    data object SwitchCamera : CallEffect
    data class SetRoute(val route: AudioRoute) : CallEffect
    data class StartTimer(val timer: CallTimer, val callId: String) : CallEffect
    data object CancelTimers : CallEffect
    data object ReleaseEngine : CallEffect
}

data class CallTransition(val state: CallState, val effects: List<CallEffect> = emptyList())

class CallStateMachine(private val selfOdinId: String, private val selfDevice: String? = null) {

    fun reduce(state: CallState, event: CallEvent, nowMs: Long): CallTransition =
        decide(state, event, nowMs).withLocalVideoTrack().stampEnd(state, nowMs).also { log(state, event, it) }

    private fun CallTransition.stampEnd(before: CallState, nowMs: Long): CallTransition =
        if (state.phase is CallPhase.Ended && before.phase !is CallPhase.Ended) copy(state = state.copy(endedAtMs = nowMs)) else this

    // The engine opens a camera track exactly when it builds an offer or answer with video.
    private fun CallTransition.withLocalVideoTrack(): CallTransition {
        val video = effects.firstNotNullOfOrNull {
            when (it) {
                is CallEffect.CreateOffer -> it.video
                is CallEffect.CreateAnswer -> it.video
                else -> null
            }
        } ?: return this
        return copy(state = state.copy(localVideoTrack = video))
    }

    private fun log(before: CallState, event: CallEvent, t: CallTransition) {
        val after = t.state
        val notable = (event is CallEvent.TimerFired && event.callId == before.callId) ||(event is CallEvent.Media && event.event !is MediaEvent.LocalIce)
        if (before.phase == after.phase && before.callId == after.callId && !notable) return
        val hangup = t.effects.filterIsInstance<CallEffect.SendSignal>().map { it.signal }.filterIsInstance<CallSignal.Hangup>().firstOrNull()
        Logger.i(tag = TAG) {
            "call=${after.callId} ${before.phase} -> ${after.phase} on ${describe(event)}" +
                (if (after.phase == CallPhase.Reconnecting) " attempt=${after.reconnectAttempt}" else "") +
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
        is CallEvent.PushRing -> pushRing(state, event, nowMs)
        is CallEvent.PushHangup ->
            if (state.phase == CallPhase.Incoming && state.callId == event.callId) end(state, CallEndReason.Cancelled)
            else CallTransition(state)
        is CallEvent.Verification ->
            if (state.isActive && state.callId == event.callId) CallTransition(state.copy(verificationCode = event.code))
            else CallTransition(state)
        is CallEvent.TimerFired -> timer(state, event)
        is CallEvent.Media -> media(state, event.event, nowMs)
        is CallEvent.EngineError -> failed(state, event.reason)
        CallEvent.NoLocalVideoTrack -> CallTransition(state.copy(localVideoTrack = false))
        is CallEvent.SetMuted -> control(state, CallEffect.SetMuted(event.muted)) { it.copy(muted = event.muted) }
        is CallEvent.SetVideo ->
            if (state.phase == CallPhase.Incoming && !event.on) CallTransition(state.copy(media = state.media.copy(localVideo = false)))
            else if (event.on && state.phase.isInCall && !state.localVideoTrack) addVideo(state)
            else control(state, CallEffect.SetVideo(event.on)) { it.copy(localVideo = event.on) }
        CallEvent.SwitchCamera -> {
            Logger.i(tag = TAG) { "call=${state.callId} SwitchCamera in ${state.phase}" }
            control(state, CallEffect.SwitchCamera) {
                it.copy(camera = if (it.camera == CameraFacing.Front) CameraFacing.Back else CameraFacing.Front)
            }
        }
        is CallEvent.SetRoute -> control(state, CallEffect.SetRoute(event.route)) { it.copy(route = event.route) }
    }

    private fun userCall(s: CallState, e: CallEvent.UserCall): CallTransition {
        if (s.isActive || e.peer == selfOdinId) return CallTransition(s)
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

    // A video call starts on the speaker, so a voice call that turns its camera on leaves the earpiece too.
    private fun addVideo(s: CallState): CallTransition {
        val toSpeaker = s.media.route == AudioRoute.Earpiece
        val media = s.media.copy(localVideo = true, route = if (toSpeaker) AudioRoute.Speaker else s.media.route)
        return CallTransition(
            s.copy(media = media, localVideoTrack = true),
            listOfNotNull(CallEffect.AddVideo, CallEffect.SetRoute(AudioRoute.Speaker).takeIf { toSpeaker }),
        )
    }

    private fun userAccept(s: CallState): CallTransition {
        if (s.phase != CallPhase.Incoming) return CallTransition(s)
        val offer = s.pendingOfferSdp ?: return CallTransition(s.copy(acceptPending = true))
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
        if (sig is CallSignal.Hangup && sig.reason.isElsewhere) return elsewhere(s, inbound.from, sig)
        if (!s.isActive || sig.callId != s.callId) return CallTransition(s)
        if (inbound.from != s.peer) return CallTransition(s)
        if (!s.isFromPeerDevice(sig.device)) return otherCalleeDevice(s, sig)
        return when (sig) {
            is CallSignal.Offer -> CallTransition(s)
            is CallSignal.Ringing ->
                if (s.phase == CallPhase.Outgoing) CallTransition(s.copy(peerRinging = true)) else CallTransition(s)
            is CallSignal.Answer ->
                if (s.phase == CallPhase.Outgoing) {
                    CallTransition(
                        s.copy(phase = CallPhase.Connecting, peerRinging = false, peerDevice = sig.device),
                        listOf(
                            CallEffect.CancelTimers,
                            relayToCalleeDevices(s, HangupReason.AnsweredElsewhere, chosen = sig.device),
                            CallEffect.ApplyRemoteAnswer(sig.sdp),
                            CallEffect.StartTimer(CallTimer.Connect, s.callId),
                        ),
                    )
                } else {
                    CallTransition(s)
                }
            is CallSignal.Restart ->
                if (s.phase.isInCall) CallTransition(s, listOf(CallEffect.AnswerRestart(sig.sdp))) else CallTransition(s)
            is CallSignal.RestartAnswer -> restartAnswer(s, sig.sdp)
            is CallSignal.RestartRequest ->
                if (s.direction == CallDirection.Outgoing && (s.phase == CallPhase.Connected || s.phase == CallPhase.Reconnecting)) {
                    CallTransition(s.restarting(), listOf(CallEffect.RestartIce))
                } else {
                    CallTransition(s)
                }
            is CallSignal.Ice -> CallTransition(s, listOf(CallEffect.AddIce(sig.candidate, sig.sdpMid, sig.sdpMLineIndex)))
            is CallSignal.Hangup -> when (s.phase) {
                CallPhase.Outgoing -> end(
                    s,
                    if (sig.reason == HangupReason.Busy) CallEndReason.Busy else CallEndReason.Declined,
                    notify = when (sig.reason) {
                        HangupReason.Declined -> HangupReason.DeclinedElsewhere
                        // One busy device ends the call, so its sibling devices must stop ringing too.
                        HangupReason.Busy -> HangupReason.Cancelled
                        else -> null
                    },
                )
                CallPhase.Incoming -> end(
                    s,
                    if (sig.reason == HangupReason.Missed) CallEndReason.Missed else CallEndReason.Cancelled,
                )
                else -> end(s, CallEndReason.Hangup(remote = true))
            }
        }
    }

    // The relay cannot loop a signal back to our own identity, so the caller tells the callee's other devices which one took the call.
    private fun relayToCalleeDevices(s: CallState, reason: HangupReason, chosen: String? = null) =
        CallEffect.SendSignal(s.peer!!, CallSignal.Hangup(s.callId!!, reason, chosen))

    // A sibling still ringing ends; one that also answered ends once it learns another device was [CallSignal.Hangup.chosen].
    private fun elsewhere(s: CallState, from: String, sig: CallSignal.Hangup): CallTransition {
        if (from != s.peer || s.callId != sig.callId) return CallTransition(s)
        val lostTheAnswer = sig.reason == HangupReason.AnsweredElsewhere && sig.chosen != null && sig.chosen != selfDevice &&
            s.direction == CallDirection.Incoming && s.phase.isInCall
        if (s.phase != CallPhase.Incoming && !lostTheAnswer) return CallTransition(s)
        return end(s, if (sig.reason == HangupReason.AnsweredElsewhere) CallEndReason.AnsweredElsewhere else CallEndReason.DeclinedElsewhere)
    }

    // A second device's answer means it missed the first relay (or raced it), so it is told again; anything else it sends is dropped.
    private fun otherCalleeDevice(s: CallState, sig: CallSignal): CallTransition {
        Logger.i(tag = TAG) { "call=${s.callId} dropped ${sig::class.simpleName} from unchosen device ${sig.device}" }
        if (sig !is CallSignal.Answer || !s.phase.isInCall) return CallTransition(s)
        return CallTransition(s, listOf(relayToCalleeDevices(s, HangupReason.AnsweredElsewhere, chosen = s.peerDevice)))
    }

    private fun offer(s: CallState, from: String, sig: CallSignal.Offer, receivedAtMs: Long, nowMs: Long): CallTransition {
        if (from == selfOdinId) return CallTransition(s)
        if (isStale(receivedAtMs, nowMs)) {
            Logger.i(tag = TAG) { "stale offer call=${sig.callId} age=${nowMs - receivedAtMs}ms dropped" }
            return CallTransition(s)
        }
        if (sig.callId == s.callId) return offerForPushedRing(s, from, sig)
        if (!s.isActive) {
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
                Logger.i(tag = TAG) { "glare: keeping own call=${s.callId}, ignoring peer offer call=${sig.callId}" }
                return CallTransition(s)
            }
            Logger.i(tag = TAG) { "glare: yielding own call=${s.callId} to peer offer call=${sig.callId}" }
            val next = s.copy(
                phase = CallPhase.Connecting,
                callId = sig.callId,
                direction = CallDirection.Incoming,
                media = s.media.copy(remoteVideo = sig.video),
                peerRinging = false,
            )
            return CallTransition(
                next,
                buildList {
                    add(CallEffect.CancelTimers)
                    add(CallEffect.ReleaseEngine)
                    // The peer's account is in this call now, so its other devices drop our ring as handled, not missed.
                    add(CallEffect.SendSignal(from, CallSignal.Hangup(s.callId!!, HangupReason.AnsweredElsewhere)))
                    add(CallEffect.CreateAnswer(sig.sdp, s.media.localVideo))
                    addAll(replayControls(s.media))
                    add(CallEffect.StartTimer(CallTimer.Connect, sig.callId))
                },
            )
        }
        Logger.i(tag = TAG) { "busy: refusing offer call=${sig.callId} from=$from while ${s.phase} call=${s.callId}" }
        return CallTransition(s, listOf(CallEffect.SendSignal(from, CallSignal.Hangup(sig.callId, HangupReason.Busy))))
    }

    // Both stamps are server time read against the device clock, so a device clock off by more than the ring timeout misjudges them.
    private fun isStale(serverSentAtMs: Long, nowMs: Long) = serverSentAtMs > 0 && nowMs - serverSentAtMs > RING_TIMEOUT_MS

    // The answering engine is new, so the controls the user set on the yielded one are applied again.
    private fun replayControls(media: CallMedia) = buildList {
        if (media.muted) add(CallEffect.SetMuted(true))
        if (media.camera != CallMedia().camera) add(CallEffect.SwitchCamera)
        if (media.route != CallMedia().route) add(CallEffect.SetRoute(media.route))
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
                        Logger.i(tag = TAG) { "call=${s.callId} link up while the ICE restart is unanswered; still reconnecting" }
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
            is MediaEvent.RemoteVideoChanged -> {
                if (e.on != s.media.remoteVideo) Logger.i(tag = TAG) { "call=${s.callId} RemoteVideoChanged on=${e.on}" }
                CallTransition(s.copy(media = s.media.copy(remoteVideo = e.on)))
            }
            is MediaEvent.RemoteMuteChanged -> CallTransition(s.copy(media = s.media.copy(remoteMuted = e.muted)))
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

    // Only the original caller drives the renegotiation, so the two sides never offer at once; the callee asks it to.
    private fun restartIce(s: CallState): List<CallEffect> =
        if (s.direction == CallDirection.Outgoing) listOf(CallEffect.RestartIce)
        else listOf(CallEffect.SendSignal(s.peer!!, CallSignal.RestartRequest(s.callId!!)))

    // A restart offer set locally but never answered leaves the caller on ICE credentials the peer
    // has not seen: the caller's link can report up while the peer's checks are rejected.
    private fun CallState.restarting() = if (direction == CallDirection.Outgoing) copy(restartPending = true) else this

    private fun reconnected(s: CallState) =
        CallTransition(s.copy(phase = CallPhase.Connected, reconnectAttempt = 0, linkUp = true), listOf(CallEffect.CancelTimers))

    private fun restartAnswer(s: CallState, sdp: String): CallTransition {
        if (!s.phase.isInCall || !s.restartPending) return CallTransition(s)
        val answered = s.copy(restartPending = false)
        val apply = CallEffect.ApplyRemoteAnswer(sdp)
        if (answered.phase != CallPhase.Reconnecting || !answered.linkUp) return CallTransition(answered, listOf(apply))
        return CallTransition(
            answered.copy(phase = CallPhase.Connected, reconnectAttempt = 0, linkUp = true),
            listOf(apply, CallEffect.CancelTimers),
        )
    }

    private fun pushRing(s: CallState, e: CallEvent.PushRing, nowMs: Long): CallTransition {
        if (e.from == selfOdinId || e.callId == s.callId || s.isActive) return CallTransition(s)
        if (isStale(e.sentAtMs, nowMs)) {
            Logger.i(tag = TAG) { "stale ring push call=${e.callId} age=${nowMs - e.sentAtMs}ms dropped" }
            return CallTransition(s)
        }
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
                CallEffect.CreateAnswer(sig.sdp, media.localVideo),
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

    private fun end(s: CallState, reason: CallEndReason, notify: HangupReason? = null): CallTransition {
        val effects = buildList {
            add(CallEffect.CancelTimers)
            // Camera and mic go first: the hangup may wait on a network that is gone.
            add(CallEffect.ReleaseEngine)
            if (notify != null && s.peer != null && s.callId != null) {
                add(CallEffect.SendSignal(s.peer, CallSignal.Hangup(s.callId, notify)))
            }
        }
        return CallTransition(
            s.copy(phase = CallPhase.Ended(reason), pendingOfferSdp = null, acceptPending = false, peerRinging = false),
            effects,
        )
    }
}

private const val TAG = "CallStateMachine"
