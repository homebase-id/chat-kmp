package id.homebase.chat.calling

import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.RtcConfiguration

internal actual val webRtcBackend = WebRtcBackend.WebRtcKmp

internal actual fun webRtcCompileProof(): Any = PeerConnection(RtcConfiguration())
