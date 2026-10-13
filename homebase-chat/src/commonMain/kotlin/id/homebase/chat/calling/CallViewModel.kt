package id.homebase.chat.calling

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.CallDirection
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallQuality
import id.homebase.chat.services.calling.CallSession
import id.homebase.chat.services.calling.CallVideos
import id.homebase.chat.services.calling.holdsPictureInPicture
import id.homebase.chat.services.calling.isInCall
import id.homebase.chat.services.calling.platformAudioRoutes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val ENDED_LINGER_MS = 2_400L

data class CallUiState(
    val phase: CallPhase = CallPhase.Idle,
    val callId: String? = null,
    val peer: String? = null,
    val direction: CallDirection? = null,
    val peerRinging: Boolean = false,
    val muted: Boolean = false,
    val localVideo: Boolean = false,
    val remoteVideo: Boolean = false,
    val placedWithVideo: Boolean = false,
    val route: AudioRoute = AudioRoute.Earpiece,
    val availableRoutes: List<AudioRoute> = emptyList(),
    val quality: CallQuality = CallQuality.Unknown,
    val connectedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val verificationCode: String? = null,
    val videos: CallVideos = CallVideos(),
    val minimized: Boolean = false,
    val dismissedCallId: String? = null,
) {
    val visible: Boolean
        get() = phase != CallPhase.Idle && !(phase is CallPhase.Ended && callId == dismissedCallId)

    val isVideoCall: Boolean get() = localVideo || remoteVideo

    val inProgress: Boolean get() = phase.isInCall

    val pipEligible: Boolean get() = isVideoCall && phase.holdsPictureInPicture

    val keepScreenAwake: Boolean get() = phase != CallPhase.Idle && phase !is CallPhase.Ended

    private val minimizedInCall: Boolean get() = minimized && inProgress

    val pillVisible: Boolean get() = minimizedInCall && !isVideoCall

    val tileVisible: Boolean get() = minimizedInCall && isVideoCall
}

sealed interface CallUiEvent {
    data object ConfirmEnd : CallUiEvent
}

class CallViewModel(
    private val session: CallSession,
    private val audioRoutes: () -> List<AudioRoute> = ::platformAudioRoutes,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CallUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CallUiEvent> = _events.asSharedFlow()

    private var lingerJob: Job? = null
    private var lingeringFor: String? = null

    init {
        viewModelScope.launch {
            combine(session.state, session.videos) { state, videos -> state to videos }.collect { (state, videos) ->
                val routes = if (state.isActive) audioRoutes() else emptyList()
                _uiState.update { previous ->
                    val newCall = state.callId != previous.callId
                    previous.copy(
                        phase = state.phase,
                        callId = state.callId,
                        peer = state.peer,
                        direction = state.direction,
                        peerRinging = state.peerRinging,
                        muted = state.media.muted,
                        localVideo = state.media.localVideo,
                        remoteVideo = state.media.remoteVideo,
                        placedWithVideo = state.placedWithVideo,
                        route = state.media.route,
                        availableRoutes = routes,
                        quality = state.quality,
                        connectedAtMs = state.connectedAtMs,
                        endedAtMs = state.endedAtMs,
                        verificationCode = state.verificationCode,
                        videos = videos,
                        minimized = previous.minimized && !newCall && state.phase.isInCall,
                    )
                }
                scheduleDismissal(state.phase, state.callId)
            }
        }
    }

    // Failed stays up: it carries Retry, so only the user dismisses it.
    private fun scheduleDismissal(phase: CallPhase, callId: String?) {
        val ended = phase as? CallPhase.Ended
        if (ended == null) {
            lingerJob?.cancel()
            lingeringFor = null
            return
        }
        if (ended.reason is CallEndReason.Failed || callId == null || lingeringFor == callId) return
        lingerJob?.cancel()
        lingeringFor = callId
        lingerJob = viewModelScope.launch {
            delay(ENDED_LINGER_MS)
            _uiState.update { it.copy(dismissedCallId = callId) }
        }
    }

    fun accept(withVideo: Boolean) {
        if (!withVideo && _uiState.value.localVideo) session.setVideo(false)
        session.accept()
    }

    fun decline() = session.decline()

    fun hangup() = session.hangup()

    fun toggleMute() = session.setMuted(!_uiState.value.muted)

    fun setVideo(on: Boolean) = session.setVideo(on)

    fun switchCamera() = session.switchCamera()

    fun setRoute(route: AudioRoute) = session.setAudioRoute(route)

    fun retry(withVideo: Boolean) {
        val current = _uiState.value
        if (current.phase !is CallPhase.Ended) return
        current.peer?.let { session.startCall(it, withVideo) }
    }

    fun close() {
        _uiState.update { it.copy(dismissedCallId = it.callId) }
    }

    fun minimize() {
        _uiState.update { if (it.inProgress) it.copy(minimized = true) else it }
    }

    fun restore() {
        _uiState.update { it.copy(minimized = false) }
    }

    fun endFromKeyboard() {
        when (_uiState.value.phase) {
            CallPhase.Connecting, CallPhase.Connected, CallPhase.Reconnecting -> _events.tryEmit(CallUiEvent.ConfirmEnd)
            CallPhase.Incoming -> session.decline()
            CallPhase.Outgoing -> session.hangup()
            is CallPhase.Ended -> close()
            CallPhase.Idle -> Unit
        }
    }
}
