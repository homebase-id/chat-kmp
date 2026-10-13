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
import id.homebase.chat.services.calling.isInCall
import id.homebase.chat.services.calling.MediaDevice
import id.homebase.chat.services.calling.MediaDeviceKind
import id.homebase.chat.services.calling.MediaDeviceNotice
import id.homebase.chat.services.calling.platformAudioRoutes
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
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
    val route: AudioRoute = AudioRoute.Earpiece,
    val availableRoutes: List<AudioRoute> = emptyList(),
    val quality: CallQuality = CallQuality.Unknown,
    val connectedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val verificationCode: String? = null,
    val videos: CallVideos = CallVideos(),
    val minimized: Boolean = false,
    val dismissedCallId: String? = null,
    val devices: List<MediaDevice> = emptyList(),
    val selectedDevices: Map<MediaDeviceKind, String?> = emptyMap(),
    val micLevel: Float = 0f,
) {
    val visible: Boolean
        get() = phase != CallPhase.Idle && !(phase is CallPhase.Ended && callId == dismissedCallId)

    val isVideoCall: Boolean get() = localVideo || remoteVideo

    val inProgress: Boolean get() = phase.isInCall

    val pipEligible: Boolean get() = isVideoCall && (phase == CallPhase.Connected || phase == CallPhase.Reconnecting)

    val keepScreenAwake: Boolean get() = inProgress

    val pillVisible: Boolean get() = minimized && inProgress

    val endReason: CallEndReason? get() = (phase as? CallPhase.Ended)?.reason

    val failed: Boolean get() = endReason is CallEndReason.Failed
}

sealed interface CallUiEvent {
    data object ShowVerification : CallUiEvent
    data object ConfirmEnd : CallUiEvent
    data class DeviceDisconnected(val name: String, val fallbackName: String) : CallUiEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModel(
    private val session: CallSession,
    private val audioRoutes: () -> List<AudioRoute> = ::platformAudioRoutes,
    private val deviceDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CallUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CallUiEvent> = _events.asSharedFlow()

    private var lingerJob: Job? = null
    private var lingeringFor: String? = null

    private class DeviceSnapshot(
        val devices: List<MediaDevice> = emptyList(),
        val selected: Map<MediaDeviceKind, String?> = emptyMap(),
        val micLevel: Float = 0f,
    )

    init {
        viewModelScope.launch {
            session.mediaDevices
                .flatMapLatest { d -> if (d == null) flowOf(DeviceSnapshot()) else combine(d.devices, d.selected, d.micLevel, ::DeviceSnapshot) }
                .collect { snap ->
                    _uiState.update { it.copy(devices = snap.devices, selectedDevices = snap.selected, micLevel = snap.micLevel) }
                }
        }
        viewModelScope.launch {
            session.mediaDevices.flatMapLatest { it?.events ?: emptyFlow() }.collect { notice ->
                if (notice is MediaDeviceNotice.Disconnected) _events.tryEmit(CallUiEvent.DeviceDisconnected(notice.name, notice.fallbackName))
            }
        }
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
                        route = state.media.route,
                        availableRoutes = routes,
                        quality = state.quality,
                        connectedAtMs = state.connectedAtMs,
                        endedAtMs = when {
                            state.phase !is CallPhase.Ended -> null
                            previous.phase is CallPhase.Ended && !newCall -> previous.endedAtMs
                            else -> Clock.System.now().toEpochMilliseconds()
                        },
                        verificationCode = state.verificationCode,
                        videos = videos,
                        minimized = previous.minimized && !newCall,
                    )
                }
                scheduleDismissal(state.phase, state.callId)
            }
        }
    }

    // Failed stays up: it carries Retry, so only the user dismisses it.
    private fun scheduleDismissal(phase: CallPhase, callId: String?) {
        val ended = phase as? CallPhase.Ended
        if (ended == null || ended.reason is CallEndReason.Failed || callId == null) {
            if (ended == null) {
                lingerJob?.cancel()
                lingeringFor = null
            }
            return
        }
        if (lingeringFor == callId) return
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

    fun toggleVideo() = session.setVideo(!_uiState.value.localVideo)

    fun switchCamera() = session.switchCamera()

    fun setRoute(route: AudioRoute) = session.setAudioRoute(route)

    // Swapping a device restarts native audio or capture, which takes long enough to drop frames on the main thread.
    fun selectDevice(kind: MediaDeviceKind, id: String) {
        val devices = session.mediaDevices.value ?: return
        viewModelScope.launch(deviceDispatcher) { devices.select(kind, id) }
    }

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

    fun onVerificationClick() {
        _events.tryEmit(CallUiEvent.ShowVerification)
    }

    fun requestClose() {
        when (callWindowCloseAction(_uiState.value.phase)) {
            CallWindowCloseAction.Decline -> session.decline()
            CallWindowCloseAction.Hangup -> session.hangup()
            CallWindowCloseAction.ConfirmEnd -> _events.tryEmit(CallUiEvent.ConfirmEnd)
            CallWindowCloseAction.Dismiss -> close()
        }
    }

    fun endFromKeyboard() {
        when (_uiState.value.phase) {
            CallPhase.Connected -> _events.tryEmit(CallUiEvent.ConfirmEnd)
            CallPhase.Incoming -> session.decline()
            is CallPhase.Ended -> close()
            CallPhase.Idle -> Unit
            else -> session.hangup()
        }
    }
}
