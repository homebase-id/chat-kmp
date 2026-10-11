package id.homebase.chat.calling

import dev.onvoid.webrtc.PeerConnectionFactory

internal actual val webRtcBackend = WebRtcBackend.WebRtcJava

internal actual fun webRtcCompileProof(): Any = PeerConnectionFactory()
