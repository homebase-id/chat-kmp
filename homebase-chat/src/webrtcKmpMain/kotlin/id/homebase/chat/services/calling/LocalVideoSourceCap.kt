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

/** Bounds the video sender to [SendVideoCap]'s bitrate window keeping frame rate: WebRTC adapts resolution only. */
internal expect fun configureVideoSender(sender: RtpSender)

/** Sets every encoding's scaleResolutionDownBy, as [SendResolutionLadder] decides. */
internal expect fun scaleVideoSender(sender: RtpSender, scaleDownBy: Double)

/** The sender's live parameters (negotiated codec, bitrate window, degradation) as one log line. */
internal expect fun describeVideoSender(sender: RtpSender): String

/** Sets every encoding's maxFramerate, as [DevicePressure] decides. */
internal expect fun limitVideoSenderFps(sender: RtpSender, fps: Int)

/** Reports the device's thermal state and Low Power Mode now and on each change, never where the platform has neither. Returns the stop. */
internal expect fun observeDevicePressure(onChange: (DevicePressure) -> Unit): () -> Unit
