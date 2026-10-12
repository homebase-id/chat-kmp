package id.homebase.chat.services.calling

import com.shepeliev.webrtckmp.MediaStreamConstraintsBuilder
import com.shepeliev.webrtckmp.RtpSender

/** Caps what the local camera source hands the encoder at [SendVideoCap]; must run before the first PeerConnection. */
internal expect fun capLocalVideoSource()

/** Asks for the camera at [SendVideoCap] where the platform picks its capture format safely, else at its default. */
internal expect fun MediaStreamConstraintsBuilder.requestCamera()

/** Bounds the video sender to [SendVideoCap]'s bitrate window with BALANCED degradation, so WebRTC adapts resolution. */
internal expect fun configureVideoSender(sender: RtpSender)

/** The sender's live parameters (negotiated codec, bitrate window, degradation) as one log line. */
internal expect fun describeVideoSender(sender: RtpSender): String
