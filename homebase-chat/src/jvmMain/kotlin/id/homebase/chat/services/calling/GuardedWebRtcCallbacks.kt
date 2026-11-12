package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation

// A Throwable that leaves a callback runs on a WebRTC native thread with no handler above it: the JVM aborts.
internal inline fun guardNative(name: String, onError: (Throwable) -> Unit, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        Logger.e(t, "WebRtcJava") { "callback $name threw, contained before reaching native" }
        try {
            onError(t)
        } catch (_: Throwable) {
        }
    }
}

internal class GuardedPeerObserver(
    private val mapper: PeerEventMapper,
    private val isClosed: () -> Boolean,
    private val setLive: (Boolean) -> Unit,
    private val track: (RTCRtpTransceiver) -> Unit,
    private val onError: (Throwable) -> Unit,
) : PeerConnectionObserver {
    override fun onIceCandidate(candidate: RTCIceCandidate) = guardNative("onIceCandidate", onError) {
        if (!isClosed()) mapper.localIce(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
    }

    override fun onConnectionChange(state: RTCPeerConnectionState) = guardNative("onConnectionChange", onError) {
        setLive(state == RTCPeerConnectionState.CONNECTED)
        if (!isClosed()) mapper.linkState(state.toLinkState())
    }

    override fun onTrack(transceiver: RTCRtpTransceiver) = guardNative("onTrack", onError) { track(transceiver) }
}

internal class GuardedCreateObserver(
    private val cont: CancellableContinuation<RTCSessionDescription>,
    private val onError: (Throwable) -> Unit,
) : CreateSessionDescriptionObserver {
    override fun onSuccess(description: RTCSessionDescription) = guardNative("createOnSuccess", onError) {
        if (cont.isActive) cont.resume(description)
    }

    override fun onFailure(error: String) = guardNative("createOnFailure", onError) {
        if (cont.isActive) cont.resumeWithException(CallEngineException(FailReason.Other, error))
    }
}

internal class GuardedSetObserver(
    private val cont: CancellableContinuation<Unit>,
    private val onError: (Throwable) -> Unit,
) : SetSessionDescriptionObserver {
    override fun onSuccess() = guardNative("setOnSuccess", onError) {
        if (cont.isActive) cont.resume(Unit)
    }

    override fun onFailure(error: String) = guardNative("setOnFailure", onError) {
        if (cont.isActive) cont.resumeWithException(CallEngineException(FailReason.Other, error))
    }
}

internal fun RTCPeerConnectionState.toLinkState() = when (this) {
    RTCPeerConnectionState.NEW -> PeerLinkState.New
    RTCPeerConnectionState.CONNECTING -> PeerLinkState.Connecting
    RTCPeerConnectionState.CONNECTED -> PeerLinkState.Connected
    RTCPeerConnectionState.DISCONNECTED -> PeerLinkState.Disconnected
    RTCPeerConnectionState.FAILED -> PeerLinkState.Failed
    RTCPeerConnectionState.CLOSED -> PeerLinkState.Closed
}
