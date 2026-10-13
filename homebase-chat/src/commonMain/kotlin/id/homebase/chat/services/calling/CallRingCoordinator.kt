package id.homebase.chat.services.calling

import id.homebase.core.notifications.CallPushRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

interface CallPushTarget {
    fun onPushRing(callId: String, from: String)
    fun onPushHangup(callId: String)
    fun accept() = Unit
    fun decline() = Unit
}

interface CallRingSink {
    fun ring(callId: String, label: String)
    fun stopRinging(callId: String)
    fun connected(callId: String)
    fun missed(peerId: String, label: String) = Unit
    fun ended(callId: String)
    fun setLive(live: Boolean, video: Boolean, callId: String?)
}

class CallRingCoordinator(
    private val target: CallPushTarget,
    private val state: StateFlow<CallState>,
    private val sink: CallRingSink,
    private val labelFor: suspend (odinId: String) -> String?,
    private val scope: CoroutineScope,
    private val labelTimeoutMs: Long = 1_500,
) : CallPushRouter.Handler {
    private val lock = Mutex()
    private var ringingId: String? = null

    fun start() {
        scope.launch {
            var labelJob: Job? = null
            var live = false
            var liveVideo = false
            var established: String? = null
            var missedFor: String? = null
            state.collect { s ->
                val callId = s.callId
                val nowRinging = callId.takeIf { s.isRinging }
                lock.withLock {
                    val previous = ringingId
                    if (previous != null && previous != nowRinging) {
                        labelJob?.cancel()
                        ringingId = null
                        sink.stopRinging(previous)
                    }
                    if (nowRinging != null && previous != nowRinging) {
                        ringingId = nowRinging
                        sink.ring(nowRinging, s.peer.orEmpty())
                        labelJob = launchLabelUpgrade(nowRinging, s.peer)
                    }
                }

                val nowLive = s.phase.isLive || (s.phase == CallPhase.Incoming && s.acceptPending)
                if (nowLive != live || (nowLive && s.media.localVideo != liveVideo)) {
                    sink.setLive(nowLive, s.media.localVideo, callId)
                    live = nowLive
                    liveVideo = s.media.localVideo
                }
                if (callId != null && s.phase == CallPhase.Connected && established != callId) {
                    established = callId
                    sink.connected(callId)
                }
                if (callId != null && s.phase is CallPhase.Ended) sink.ended(callId)
                val peer = s.peer
                if (callId != null && peer != null && missedFor != callId && s.isMissedIncoming()) {
                    missedFor = callId
                    scope.launch {
                        val label = withTimeoutOrNull(labelTimeoutMs) { runCatching { labelFor(peer) }.getOrNull() }
                        sink.missed(peer, label?.takeIf { it.isNotBlank() } ?: peer)
                    }
                }
            }
        }
    }

    private fun launchLabelUpgrade(callId: String, peer: String?): Job? {
        if (peer == null) return null
        return scope.launch {
            val label = withTimeoutOrNull(labelTimeoutMs) { runCatching { labelFor(peer) }.getOrNull() }
            if (label.isNullOrBlank() || label == peer) return@launch
            lock.withLock { if (ringingId == callId) sink.ring(callId, label) }
        }
    }

    fun accept(callId: String) {
        if (state.value.isRingingFor(callId)) target.accept()
    }

    fun decline(callId: String) {
        if (state.value.isRingingFor(callId)) target.decline()
    }

    override fun onRing(callId: String, from: String, message: String?) = target.onPushRing(callId, from)

    override fun onHangup(callId: String, from: String) = target.onPushHangup(callId)

    private val CallState.isRinging: Boolean
        get() = callId?.let { isRingingFor(it) } == true

    private val CallPhase.isLive: Boolean
        get() = isInCall || this == CallPhase.Outgoing
}

private fun CallState.isMissedIncoming(): Boolean =
    direction == CallDirection.Incoming && connectedAtMs == null &&
        (phase as? CallPhase.Ended)?.reason.let { it == CallEndReason.Missed || it == CallEndReason.Cancelled }

fun CallState.isRingingFor(callId: String): Boolean =
    this.callId == callId && phase == CallPhase.Incoming && direction == CallDirection.Incoming && !acceptPending
