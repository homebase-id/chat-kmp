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
}

/** Implemented in Kotlin: what CallKit's answer/end buttons do. */
interface CallKitActions {
    fun accept(callId: String)
    fun endOrDecline(callId: String)
    fun setMuted(muted: Boolean)
}

object CallKitBridgeHolder {
    var bridge: CallKitBridge? = null
    var actions: CallKitActions? = null
}
