package id.homebase.chat.services.calling

import kotlin.concurrent.Volatile

enum class PeerLinkState { New, Connecting, Connected, Disconnected, Failed, Closed }

/** Native peer-connection callbacks (any thread) to [MediaEvent]s; the one mapping both engines share. */
class PeerEventMapper(private val emit: (MediaEvent) -> Unit) {
    @Volatile
    private var everConnected = false

    fun linkState(state: PeerLinkState) {
        when (state) {
            PeerLinkState.Connected -> {
                everConnected = true
                emit(MediaEvent.Connected)
            }
            PeerLinkState.Disconnected -> emit(MediaEvent.Disconnected)
            PeerLinkState.Failed -> emit(if (everConnected) MediaEvent.Disconnected else MediaEvent.Failed(FailReason.NoRoute))
            PeerLinkState.New, PeerLinkState.Connecting, PeerLinkState.Closed -> Unit
        }
    }

    fun localIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) =
        emit(MediaEvent.LocalIce(candidate, sdpMid, sdpMLineIndex))

    fun remoteVideo(on: Boolean) = emit(MediaEvent.RemoteVideoChanged(on))
}
