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

    private data class Key(val callId: String?, val phase: CallPhase, val direction: CallDirection?, val video: Boolean)

    suspend fun run() {
        session.state.map { Key(it.callId, it.phase, it.direction, it.placedWithVideo) }.distinctUntilChanged().collect { (callId, phase, direction, video) ->
            val bridge = bridge() ?: return@collect
            val id = callId ?: return@collect
            if (direction == CallDirection.Outgoing && !bridge.hostsOutgoingCalls) return@collect
            val state = session.state.value
            when (phase) {
                CallPhase.Outgoing -> if (reported != id) {
                    reported = id
                    bridge.reportOutgoing(id, state.peer.orEmpty(), state.media.localVideo)
                }
                CallPhase.Incoming, CallPhase.Connecting -> if (direction == CallDirection.Incoming && video) bridge.reportVideo(id, true)
                CallPhase.Connected -> bridge.reportConnected(id)
                is CallPhase.Ended -> {
                    bridge.reportEnded(id, remoteEnded = direction == CallDirection.Incoming || phase.reason is CallEndReason.Hangup)
                    if (reported == id) reported = null
                }
                else -> Unit
            }
        }
    }
}
