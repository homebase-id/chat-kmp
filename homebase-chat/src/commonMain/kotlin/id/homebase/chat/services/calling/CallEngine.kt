package id.homebase.chat.services.calling

import kotlinx.coroutines.flow.Flow

class CallEngineException(val reason: FailReason, message: String? = null) : Exception(message)

interface CallEngine {
    val events: Flow<MediaEvent>

    suspend fun createOffer(video: Boolean): String
    suspend fun answer(offerSdp: String, video: Boolean): String
    suspend fun applyRemote(answerSdp: String)
    suspend fun addIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int?)
    fun setMuted(muted: Boolean)
    fun setVideo(on: Boolean)
    fun switchCamera()
    fun setAudioRoute(route: AudioRoute)
    fun close()
}

fun interface CallEngineFactory {
    fun create(): CallEngine
}

interface CallSignaling {
    val incoming: Flow<InboundSignal>

    suspend fun send(peer: String, signal: CallSignal)
}
