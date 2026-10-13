package id.homebase.chat.services.calling

import id.homebase.core.notifications.CallKitBridge
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Mirrors the call lifecycle to CallKit so iOS shows its call UI; an outgoing call is only mirrored when the host can carry it. */
class CallKitMirror(
    private val session: CallSession,
    private val bridge: () -> CallKitBridge?,
) {
    private var reported: String? = null
    private var answered: String? = null

    private data class Key(val callId: String?, val phase: CallPhase, val direction: CallDirection?, val video: Boolean, val answeredInApp: Boolean)

    suspend fun run() {
        session.state.map { Key(it.callId, it.phase, it.direction, it.placedWithVideo, it.answeredInApp) }.distinctUntilChanged().collect { (callId, phase, direction, video, answeredInApp) ->
            val bridge = bridge() ?: return@collect
            val id = callId ?: return@collect
            if (direction == CallDirection.Outgoing && !bridge.hostsOutgoingCalls) return@collect
            val state = session.state.value
            if (answeredInApp && answered != id) {
                answered = id
                bridge.requestAnswer(id)
            }
            when (phase) {
                CallPhase.Outgoing -> if (reported != id) {
                    reported = id
                    bridge.reportOutgoing(id, state.peer.orEmpty(), state.media.localVideo)
                }
                CallPhase.Incoming, CallPhase.Connecting -> if (direction == CallDirection.Incoming && video) bridge.reportVideo(id, true)
                CallPhase.Connected -> if (direction == CallDirection.Outgoing) bridge.reportConnected(id)
                is CallPhase.Ended -> {
                    val reason = phase.reason
                    if (reason == CallEndReason.AnsweredElsewhere || reason == CallEndReason.DeclinedElsewhere) {
                        bridge.reportEndedElsewhere(id, declined = reason == CallEndReason.DeclinedElsewhere)
                    } else if (phase.reason.endedLocally(direction)) bridge.requestEnd(id) else bridge.reportEnded(id, remoteEnded = phase.reason !is CallEndReason.Failed)
                    if (reported == id) reported = null
                    if (answered == id) answered = null
                }
                else -> Unit
            }
        }
    }
}

private val CallState.answeredInApp: Boolean
    get() = direction == CallDirection.Incoming &&
        (acceptPending || phase.isInCall)

private fun CallEndReason.endedLocally(direction: CallDirection?): Boolean = when (this) {
    is CallEndReason.Hangup -> !remote
    CallEndReason.Declined -> direction == CallDirection.Incoming
    CallEndReason.Cancelled -> direction == CallDirection.Outgoing
    else -> false
}
