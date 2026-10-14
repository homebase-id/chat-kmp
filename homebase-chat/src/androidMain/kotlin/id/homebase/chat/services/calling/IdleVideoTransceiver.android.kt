package id.homebase.chat.services.calling

import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.RtpTransceiver
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpTransceiver.RtpTransceiverDirection
import org.webrtc.RtpTransceiver.RtpTransceiverInit

internal actual fun PeerConnection.addIdleVideoTransceiver() {
    android.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiverInit(RtpTransceiverDirection.SEND_RECV))
}

internal actual val RtpTransceiver.isVideo: Boolean
    get() = native.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
