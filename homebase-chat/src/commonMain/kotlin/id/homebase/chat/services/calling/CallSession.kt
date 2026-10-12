package id.homebase.chat.services.calling

import kotlinx.coroutines.flow.StateFlow

interface CallSession {
    val state: StateFlow<CallState>
    val videos: StateFlow<CallVideos>

    fun startCall(peer: String, video: Boolean)
    fun accept()
    fun decline()
    fun hangup()
    fun setMuted(muted: Boolean)
    fun setVideo(on: Boolean)
    fun switchCamera()
    fun setAudioRoute(route: AudioRoute)
}
