package id.homebase.chat.services.calling

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class CallEngineException(val reason: FailReason, message: String? = null) : Exception(message)

/** Opaque per-platform video source; only the matching [CallVideoView] actual can render it. */
interface VideoHandle

data class CallVideos(val local: VideoHandle? = null, val remote: VideoHandle? = null)

interface CallEngine {
    val events: Flow<MediaEvent>
    val localVideo: StateFlow<VideoHandle?>
    val remoteVideo: StateFlow<VideoHandle?>
    val mediaDevices: CallMediaDevices? get() = null

    suspend fun createOffer(video: Boolean): String
    suspend fun answer(offerSdp: String, video: Boolean): String
    suspend fun applyRemote(answerSdp: String)
    suspend fun restartIce(): String
    suspend fun answerRestart(offerSdp: String): String
    suspend fun addIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int?)
    fun setMuted(muted: Boolean)
    fun setVideo(on: Boolean)
    fun switchCamera()
    fun setAudioRoute(route: AudioRoute)
    fun close()
}

fun interface CallEngineFactory {
    /** [stunUrls] are the device's own server's `stun:` URLs, used as given. */
    fun create(stunUrls: List<String>): CallEngine
}

