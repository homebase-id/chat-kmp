package id.homebase.chat.services.calling

import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.RtpTransceiver

/** A send-receive video transceiver with no track, so either side can start a camera later without renegotiating. */
internal expect fun PeerConnection.addIdleVideoTransceiver()

internal expect val RtpTransceiver.isVideo: Boolean
