package id.homebase.chat.services.calling

import com.shepeliev.webrtckmp.MediaStreamConstraintsBuilder
import com.shepeliev.webrtckmp.RtpSender

/**
 * One-time PeerConnectionFactory setup; must run before the first PeerConnection. iOS caps what the camera source hands
 * the encoder at [SendVideoCap]; Android routes libwebrtc's own log into homebase.log.
 */
internal expect fun configureWebRtc()

/** Asks for the camera at [SendVideoCap] where the platform picks its capture format safely, else at its default. */
internal expect fun MediaStreamConstraintsBuilder.requestCamera()

/** Caps the video sender at [SendVideoCap] with BALANCED degradation, so WebRTC adapts resolution. */
internal expect fun configureVideoSender(sender: RtpSender)

/** A [SendResolutionLadder] that scales [sender]'s encodings, where the platform needs one. */
internal expect fun sendResolutionLadder(sender: () -> RtpSender?): SendResolutionLadder?

/** The sender's live parameters (negotiated codec, bitrate window, degradation) as one log line. */
internal expect fun describeVideoSender(sender: RtpSender): String
