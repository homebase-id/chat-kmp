package id.homebase.core.notifications

/** Implemented in Swift: reports calls to CallKit so iOS shows the system call UI and owns the audio session. */
interface CallKitBridge {
    /** False where the OS cannot host a CallKit call: the iOS Simulator has no in-call UI, so callservicesd disconnects an outgoing call about a second after it starts. */
    val hostsOutgoingCalls: Boolean

    fun reportOutgoing(callId: String, handle: String, video: Boolean)
    fun reportConnected(callId: String)
    /** The ring push does not say whether the call is video; the offer does, so CallKit's call is updated once it lands. */
    fun reportVideo(callId: String, video: Boolean)
    fun reportEnded(callId: String, remoteEnded: Boolean)
    /** Another device of this identity took or declined the call, so CallKit records it as handled elsewhere instead of missed. */
    fun reportEndedElsewhere(callId: String, declined: Boolean)
    /** The user accepted a call CallKit is still ringing from inside the app; CallKit must answer it too or it never activates the audio session. */
    fun requestAnswer(callId: String)
    /** The user ended the call from inside the app; CallKit ends it through a CXEndCallAction so its UI and history match. */
    fun requestEnd(callId: String)
}

/** Implemented in Kotlin: what CallKit's answer/end buttons do. */
interface CallKitActions {
    fun accept(callId: String)
    fun endOrDecline(callId: String)
    fun setMuted(muted: Boolean)
}

/** Implemented in Swift: shows the remote call video in the system PiP window; AVKit's video-call content source has no Kotlin binding. */
interface CallPictureInPictureBridge {
    /** [remoteTrack] is the WebRTC RTCVideoTrack. PiP then starts by itself when the app backgrounds. [log] reaches homebase.log; NSLog does not. */
    fun start(remoteTrack: Any, log: (String) -> Unit)
    fun stop()
}

/** Holds the PushKit token until Koin is up (App.init runs before initializeApp, which a pending crash report skips). */
object VoipTokenRelay {
    @kotlin.concurrent.Volatile
    private var token: String? = null

    @kotlin.concurrent.Volatile
    private var sink: ((String) -> Unit)? = null

    fun onToken(token: String) {
        this.token = token
        sink?.invoke(token)
    }

    fun attach(sink: (String) -> Unit) {
        this.sink = sink
        token?.let(sink)
    }
}

object CallKitBridgeHolder {
    var bridge: CallKitBridge? = null
    var actions: CallKitActions? = null
}

object CallPictureInPictureBridgeHolder {
    var bridge: CallPictureInPictureBridge? = null
}
