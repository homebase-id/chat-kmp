package id.homebase.chat.services.calling

import WebRTC.RTCDegradationPreference
import WebRTC.RTCRtpCodecParameters
import WebRTC.RTCRtpEncodingParameters
import com.shepeliev.webrtckmp.MediaStreamConstraintsBuilder
import com.shepeliev.webrtckmp.RtpSender
import com.shepeliev.webrtckmp.VideoProcessorFactory
import com.shepeliev.webrtckmp.WebRtc
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNumber
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
private val configured = AtomicBoolean(false)

// webrtc-kmp 0.125.11 reads each camera format's size through a freed struct (copyContents), so it opens the iPhone
// camera at 4K and the adapter lets the encoder climb past 1080p. The source itself stays the capturer's delegate, so
// no Kotlin runs per frame.
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
internal actual fun capLocalVideoSource() {
    if (!configured.compareAndSet(false, true)) return
    WebRtc.configure(
        VideoProcessorFactory { source ->
            source.adaptOutputFormatToWidth(SendVideoCap.WIDTH, height = SendVideoCap.HEIGHT, fps = SendVideoCap.FPS)
            source
        },
    )
}

// Size constraints would be matched against the same garbage format sizes; the adapter above bounds the output instead.
internal actual fun MediaStreamConstraintsBuilder.requestCamera() = video(true)

@OptIn(ExperimentalForeignApi::class)
internal actual fun configureVideoSender(sender: RtpSender) {
    val native = sender.ios
    val params = native.parameters
    params.degradationPreference = NSNumber(long = RTCDegradationPreference.RTCDegradationPreferenceBalanced.value)
    params.encodings.filterIsInstance<RTCRtpEncodingParameters>().forEach {
        it.maxBitrateBps = NSNumber(int = SendVideoCap.MAX_BITRATE_BPS)
        it.minBitrateBps = NSNumber(int = SendVideoCap.MIN_BITRATE_BPS)
        it.maxFramerate = NSNumber(int = SendVideoCap.FPS)
    }
    native.parameters = params
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun describeVideoSender(sender: RtpSender): String {
    val params = sender.ios.parameters
    val codecs = params.codecs.filterIsInstance<RTCRtpCodecParameters>().joinToString { "${it.name}${it.parameters}" }
    val encodings = params.encodings.filterIsInstance<RTCRtpEncodingParameters>()
        .joinToString { "max=${it.maxBitrateBps} min=${it.minBitrateBps} fps=${it.maxFramerate}" }
    return "degradation=${params.degradationPreference} encodings=[$encodings] codecs=[$codecs]"
}
