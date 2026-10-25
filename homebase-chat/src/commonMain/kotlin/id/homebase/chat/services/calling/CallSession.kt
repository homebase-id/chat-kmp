package id.homebase.chat.services.calling

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface CallSession {
    val state: StateFlow<CallState>
    /** Every state change in order, starting with the current one; [state] drops the ones a slow collector misses. */
    val transitions: Flow<CallState>
    val videos: StateFlow<CallVideos>
    val mediaDevices: StateFlow<CallMediaDevices?> get() = NoCallMediaDevices

    fun startCall(peer: String, video: Boolean)
    fun accept()
    fun decline()
    fun hangup()
    fun setMuted(muted: Boolean)
    fun setVideo(on: Boolean)
    fun switchCamera()
    fun setAudioRoute(route: AudioRoute)
}
