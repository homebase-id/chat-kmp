package id.homebase.chat.services.calling

import WebRTC.RTCDegradationPreference
import WebRTC.RTCRtpCodecParameters
import WebRTC.RTCRtpEncodingParameters
import com.shepeliev.webrtckmp.MediaStreamConstraintsBuilder
import com.shepeliev.webrtckmp.RtpSender
import com.shepeliev.webrtckmp.VideoProcessorFactory
import com.shepeliev.webrtckmp.WebRtc
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoPowerStateDidChangeNotification
import platform.Foundation.NSProcessInfoThermalStateDidChangeNotification
import platform.Foundation.isLowPowerModeEnabled
import platform.Foundation.thermalState
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
private val configured = AtomicBoolean(false)

// Safety net behind pinCaptureFormat: should a capture start keep webrtc-kmp's 4K pick, the encoder still sees at most
// the send cap. The source itself stays the capturer's delegate, so no Kotlin runs per frame.
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
internal actual fun configureWebRtc() {
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
internal actual fun applyVideoSender(sender: RtpSender, state: SenderState) {
    val native = sender.ios
    val params = native.parameters
    params.degradationPreference = NSNumber(long = RTCDegradationPreference.RTCDegradationPreferenceMaintainFramerate.value)
    params.encodings.filterIsInstance<RTCRtpEncodingParameters>().forEach { encoding ->
        state.maxBitrateBps?.let { encoding.maxBitrateBps = NSNumber(int = it) }
        state.minBitrateBps?.let { encoding.minBitrateBps = NSNumber(int = it) }
        state.maxFramerate?.let { encoding.maxFramerate = NSNumber(int = it) }
        state.scaleDownBy?.let { encoding.scaleResolutionDownBy = NSNumber(double = it) }
    }
    native.parameters = params
}

internal actual fun observeDevicePressure(onChange: (DevicePressure) -> Unit): () -> Unit {
    val info = NSProcessInfo.processInfo
    val report = {
        // NSProcessInfoThermalState: Nominal 0, Fair 1, Serious 2, Critical 3 (the constants are not exported to Kotlin).
        val thermal = when (info.thermalState.value.toLong()) {
            1L -> ThermalLevel.Fair
            2L -> ThermalLevel.Serious
            3L -> ThermalLevel.Critical
            else -> ThermalLevel.Nominal
        }
        onChange(DevicePressure(thermal, info.isLowPowerModeEnabled()))
    }
    val center = NSNotificationCenter.defaultCenter
    val observers = listOf(NSProcessInfoThermalStateDidChangeNotification, NSProcessInfoPowerStateDidChangeNotification)
        .map { name -> center.addObserverForName(name, info, NSOperationQueue.mainQueue) { _ -> report() } }
    report()
    return { observers.forEach { center.removeObserver(it) } }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun describeVideoSender(sender: RtpSender): String {
    val params = sender.ios.parameters
    val codecs = params.codecs.filterIsInstance<RTCRtpCodecParameters>().joinToString { "${it.name}${it.parameters}" }
    val encodings = params.encodings.filterIsInstance<RTCRtpEncodingParameters>()
        .joinToString { "max=${it.maxBitrateBps} min=${it.minBitrateBps} fps=${it.maxFramerate} scaleDownBy=${it.scaleResolutionDownBy}" }
    return "degradation=${params.degradationPreference} encodings=[$encodings] codecs=[$codecs]"
}
