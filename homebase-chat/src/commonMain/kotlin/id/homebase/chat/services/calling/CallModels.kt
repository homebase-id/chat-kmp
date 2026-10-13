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
    val camera: CameraFacing = CameraFacing.Front,
    val muted: Boolean = false,
    val route: AudioRoute = AudioRoute.Earpiece,
)

data class CallState(
    val phase: CallPhase = CallPhase.Idle,
    val callId: String? = null,
    val peer: String? = null,
    val direction: CallDirection? = null,
    val media: CallMedia = CallMedia(),
    val connectedAtMs: Long? = null,
    val quality: CallQuality = CallQuality.Unknown,
    val reconnectAttempt: Int = 0,
    val peerRinging: Boolean = false,
    val pendingOfferSdp: String? = null,
    val offerSeq: Int = 0,
    val acceptPending: Boolean = false,
    val verificationCode: String? = null,
    val placedWithVideo: Boolean = false,
    val restartPending: Boolean = false,
    val linkUp: Boolean = false,
) {
    val isActive: Boolean
        get() = phase !is CallPhase.Idle && phase !is CallPhase.Ended
}

sealed interface MediaEvent {
    data object Connected : MediaEvent
    data object Disconnected : MediaEvent
    data class Failed(val reason: FailReason) : MediaEvent
    data class RemoteVideoChanged(val on: Boolean) : MediaEvent
    data class QualityChanged(val quality: CallQuality) : MediaEvent
    data class RouteChanged(val route: AudioRoute) : MediaEvent
    data class LocalIce(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?) : MediaEvent
}
