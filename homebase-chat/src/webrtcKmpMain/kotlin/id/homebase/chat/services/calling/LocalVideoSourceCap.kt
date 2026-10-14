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

/**
 * Writes [state] to the video sender in one setParameters call, with the platform's degradation preference. Only
 * [SenderParams] calls it, so the sender is never read-modified-written from two threads at once.
 */
internal expect fun applyVideoSender(sender: RtpSender, state: SenderState)

/** The sender's live parameters (negotiated codec, bitrate window, degradation) as one log line. */
internal expect fun describeVideoSender(sender: RtpSender): String

/** Reports the device's thermal state and Low Power Mode now and on each change; null where the platform has neither. Returns the stop. */
internal expect fun observeDevicePressure(onChange: (DevicePressure) -> Unit): () -> Unit
