package id.homebase.chat.services.calling

import id.homebase.core.calling.CallKitBridge
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Mirrors the call lifecycle to CallKit so iOS shows its call UI; an outgoing call is only mirrored when the host can carry it. */
class CallKitMirror(
    private val session: CallSession,
    private val bridge: () -> CallKitBridge?,
) {
    private var reported: String? = null
    private var answered: String? = null
    private var videoCallId: String? = null
    private var connectedReported: String? = null

    private data class Key(
        val callId: String?,
        val phase: CallPhase,
        val direction: CallDirection?,
        val video: Boolean,
        val answeredInApp: Boolean,
        val peer: String?,
        val videoOn: Boolean,
    )

    suspend fun run() {
        session.transitions.map { Key(it.callId, it.phase, it.direction, it.placedWithVideo, it.answeredInApp, it.peer, it.media.localVideo || it.media.remoteVideo) }
            .distinctUntilChanged()
            .collect { (callId, phase, direction, video, answeredInApp, peer, videoOn) ->
                val bridge = bridge() ?: return@collect
                val id = callId ?: return@collect
                if (direction == CallDirection.Outgoing && !bridge.hostsOutgoingCalls()) return@collect
                if (answeredInApp && answered != id) {
                    answered = id
                    bridge.requestAnswer(id)
                }
                when (phase) {
                    CallPhase.Outgoing -> if (reported != id) {
                        reported = id
                        videoCallId = id.takeIf { videoOn }
                        bridge.reportOutgoing(id, peer.orEmpty(), videoOn)
                    }
                    CallPhase.Incoming, CallPhase.Connecting -> if (direction == CallDirection.Incoming && video && videoCallId != id) {
                        videoCallId = id
                        bridge.reportVideo(id, true)
                    }
                    CallPhase.Connected -> {
                        if (direction == CallDirection.Outgoing && connectedReported != id) {
                            connectedReported = id
                            bridge.reportConnected(id)
                        }
                        if (videoOn != (videoCallId == id)) {
                            videoCallId = id.takeIf { videoOn }
                            bridge.reportVideo(id, videoOn)
                        }
                    }
                    is CallPhase.Ended -> {
                        val reason = phase.reason
                        if (reason == CallEndReason.AnsweredElsewhere || reason == CallEndReason.DeclinedElsewhere) {
                            bridge.reportEndedElsewhere(id, declined = reason == CallEndReason.DeclinedElsewhere)
                        } else if (phase.reason.endedLocally(direction)) bridge.requestEnd(id) else bridge.reportEnded(id, remoteEnded = phase.reason !is CallEndReason.Failed)
                        if (reported == id) reported = null
                        if (answered == id) answered = null
                        if (connectedReported == id) connectedReported = null
                        if (videoCallId == id) videoCallId = null
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
