package id.homebase.chat.services.calling

const val RING_TIMEOUT_MS = 45_000L
const val CONNECT_TIMEOUT_MS = 30_000L
const val RECONNECT_TICK_MS = 5_000L
const val OFFER_REPUBLISH_MS = 250L

// Must outlast ICE noticing the drop (~6 s) plus a phone rejoining wifi until DNS resolves again (~5 s+).
const val RECONNECT_TIMEOUT_MS = 30_000L
const val MAX_RECONNECT_ATTEMPTS = (RECONNECT_TIMEOUT_MS / RECONNECT_TICK_MS).toInt()

enum class CallDirection { Outgoing, Incoming }

enum class CameraFacing { Front, Back }

/** Only the user-facing camera's local preview is mirrored, like FaceTime/Meet; the transmitted stream never is. */
val CameraFacing.mirrorsLocalPreview: Boolean get() = this == CameraFacing.Front

enum class AudioRoute { Earpiece, Speaker, Bluetooth, Wired }

/**
 * A headset that just arrived takes the call, wired before Bluetooth; when the current route's device leaves, the call
 * falls back to speaker for video and earpiece for voice. Null keeps the current route.
 */
internal fun preferredRoute(routes: List<AudioRoute>, previous: List<AudioRoute>, current: AudioRoute?, video: Boolean): AudioRoute? = when {
    AudioRoute.Wired in routes && AudioRoute.Wired !in previous -> AudioRoute.Wired
    AudioRoute.Bluetooth in routes && AudioRoute.Bluetooth !in previous -> AudioRoute.Bluetooth
    current != null && current in routes -> null
    video -> AudioRoute.Speaker
    else -> AudioRoute.Earpiece
}

internal enum class CallAudioMode { VoiceChat, VideoChat }

// Mode is fixed per call: WebRtcKmpCallEngine has no addVideo, so a voice call never gains a camera.
internal data class CallAudioProfile(val mode: CallAudioMode, val route: AudioRoute)

internal fun callAudioProfile(video: Boolean, headset: Boolean) = CallAudioProfile(
    mode = if (video) CallAudioMode.VideoChat else CallAudioMode.VoiceChat,
    route = if (video && !headset) AudioRoute.Speaker else AudioRoute.Earpiece,
)

enum class CallQuality { Good, Poor, Unknown }

enum class FailReason { NoRoute, Permission, Other }

sealed interface CallEndReason {
    data class Hangup(val remote: Boolean) : CallEndReason
    data object Declined : CallEndReason
    data object Missed : CallEndReason
    data object Busy : CallEndReason
    data class Failed(val reason: FailReason) : CallEndReason
    data object Cancelled : CallEndReason
    data object AnsweredElsewhere : CallEndReason
    data object DeclinedElsewhere : CallEndReason
}

sealed interface CallPhase {
    data object Idle : CallPhase
    data object Outgoing : CallPhase
    data object Incoming : CallPhase
    data object Connecting : CallPhase
    data object Connected : CallPhase
    data object Reconnecting : CallPhase
    data class Ended(val reason: CallEndReason) : CallPhase
}

val CallPhase.isInCall: Boolean
    get() = this == CallPhase.Connecting || this == CallPhase.Connected || this == CallPhase.Reconnecting

data class CallMedia(
    val audio: Boolean = true,
    val localVideo: Boolean = false,
    val remoteVideo: Boolean = false,
    val remoteMuted: Boolean = false,
    val camera: CameraFacing = CameraFacing.Front,
    val muted: Boolean = false,
    val route: AudioRoute = AudioRoute.Earpiece,
)

data class CallState(
    val phase: CallPhase = CallPhase.Idle,
    val callId: String? = null,
    val peer: String? = null,
    /** The callee device whose answer the caller took; signals from the callee's other devices are dropped. */
    val peerDevice: String? = null,
    val direction: CallDirection? = null,
    val media: CallMedia = CallMedia(),
    val connectedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val quality: CallQuality = CallQuality.Unknown,
    val reconnectAttempt: Int = 0,
    val peerRinging: Boolean = false,
    val pendingOfferSdp: String? = null,
    val offerSeq: Int = 0,
    val acceptPending: Boolean = false,
    val verificationCode: String? = null,
    val placedWithVideo: Boolean = false,
    val restartPending: Boolean = false,
    /** The engine holds a camera track; without one, turning video on renegotiates through [CallEffect.AddVideo]. */
    val localVideoTrack: Boolean = false,
    val linkUp: Boolean = false,
) {
    val isActive: Boolean
        get() = phase !is CallPhase.Idle && phase !is CallPhase.Ended

    fun isFromPeerDevice(device: String?): Boolean = peerDevice == null || device == null || device == peerDevice
}

sealed interface MediaEvent {
    data object Connected : MediaEvent
    data object Disconnected : MediaEvent
    data class Failed(val reason: FailReason) : MediaEvent
    data class RemoteVideoChanged(val on: Boolean) : MediaEvent
    data class RemoteMuteChanged(val muted: Boolean) : MediaEvent
    data class QualityChanged(val quality: CallQuality) : MediaEvent
    data class RouteChanged(val route: AudioRoute) : MediaEvent
    data class LocalIce(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?) : MediaEvent
}
