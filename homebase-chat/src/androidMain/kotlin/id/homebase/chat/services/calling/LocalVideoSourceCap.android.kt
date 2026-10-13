package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger
import com.shepeliev.webrtckmp.MediaStreamConstraintsBuilder
import com.shepeliev.webrtckmp.RtpSender
import org.webrtc.RtpParameters

internal actual fun capLocalVideoSource() = Unit

// Camera2 picks the closest format the device lists, so asking for 1080p is safe here.
internal actual fun MediaStreamConstraintsBuilder.requestCamera() = video {
    width(SendVideoCap.WIDTH)
    height(SendVideoCap.HEIGHT)
    frameRate(SendVideoCap.FPS.toDouble())
}

internal actual fun configureVideoSender(sender: RtpSender) {
    val native = sender.android
    val params = native.parameters
    params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
    params.encodings.forEach {
        it.maxBitrateBps = SendVideoCap.MAX_BITRATE_BPS
        it.minBitrateBps = SendVideoCap.MIN_BITRATE_BPS
        it.maxFramerate = SendVideoCap.FPS
    }
    if (!native.setParameters(params)) Logger.w("WebRtcKmp") { "video sender rejected ${describeVideoSender(sender)}" }
}

internal actual fun scaleVideoSender(sender: RtpSender, scaleDownBy: Double) {
    val native = sender.android
    val params = native.parameters
    params.encodings.forEach { it.scaleResolutionDownBy = scaleDownBy }
    if (!native.setParameters(params)) Logger.w("WebRtcKmp") { "video sender rejected scale $scaleDownBy" }
}

internal actual fun describeVideoSender(sender: RtpSender): String {
    val params = sender.android.parameters
    val codecs = params.codecs.joinToString { "${it.name}${it.parameters}" }
    val encodings = params.encodings.joinToString { "max=${it.maxBitrateBps} min=${it.minBitrateBps} fps=${it.maxFramerate} scaleDownBy=${it.scaleResolutionDownBy}" }
    return "degradation=${params.degradationPreference} encodings=[$encodings] codecs=[$codecs]"
}
