package id.homebase.chat.services.calling

import WebRTC.RTCRtpMediaType
import WebRTC.RTCRtpTransceiverDirection
import WebRTC.RTCRtpTransceiverInit
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.RtpTransceiver
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
internal actual fun PeerConnection.addIdleVideoTransceiver() {
    val init = RTCRtpTransceiverInit().apply { direction = RTCRtpTransceiverDirection.RTCRtpTransceiverDirectionSendRecv }
    ios.addTransceiverOfType(RTCRtpMediaType.RTCRtpMediaTypeVideo, init)
}

@OptIn(ExperimentalForeignApi::class)
internal actual val RtpTransceiver.isVideo: Boolean
    get() = native.mediaType == RTCRtpMediaType.RTCRtpMediaTypeVideo
