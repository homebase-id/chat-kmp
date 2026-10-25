package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger
import com.shepeliev.webrtckmp.MediaStreamConstraintsBuilder
import com.shepeliev.webrtckmp.RtpSender
import org.webrtc.RtpParameters

internal actual fun configureWebRtc() = configureWebRtcFactory

// Camera2 picks the closest format the device lists, so asking for 720p is safe here.
internal actual fun MediaStreamConstraintsBuilder.requestCamera() = video {
    width(SendVideoCap.WIDTH)
    height(SendVideoCap.HEIGHT)
    frameRate(SendVideoCap.FPS.toDouble())
}

internal actual fun applyVideoSender(sender: RtpSender, state: SenderState) {
    val native = sender.android
    val params = native.parameters
    // MAINTAIN_FRAMERATE starts H.264 at 360p and its quality scaler waits for QP < 24, which the Qualcomm encoder never
    // reaches, so it stays at 360p; SendResolutionLadder and SendLoadRung lower the resolution instead.
    params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
    params.encodings.forEach { encoding ->
        state.maxBitrateBps?.let { encoding.maxBitrateBps = it }
        state.minBitrateBps?.let { encoding.minBitrateBps = it }
        state.maxFramerate?.let { encoding.maxFramerate = it }
        state.scaleDownBy?.let { encoding.scaleResolutionDownBy = it }
    }
    if (!native.setParameters(params)) Logger.w(tag = "WebRtcKmp") { "video sender rejected $state: ${describeVideoSender(sender)}" }
}

internal actual fun observeDevicePressure(onChange: (DevicePressure) -> Unit): () -> Unit = {}

internal actual fun describeVideoSender(sender: RtpSender): String {
    val params = sender.android.parameters
    val codecs = params.codecs.joinToString { "${it.name}${it.parameters}" }
    val encodings = params.encodings.joinToString { "max=${it.maxBitrateBps} min=${it.minBitrateBps} fps=${it.maxFramerate} scaleDownBy=${it.scaleResolutionDownBy}" }
    return "degradation=${params.degradationPreference} encodings=[$encodings] codecs=[$codecs]"
}
