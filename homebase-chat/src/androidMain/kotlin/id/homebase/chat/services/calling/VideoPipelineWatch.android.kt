package id.homebase.chat.services.calling

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import co.touchlab.kermit.Logger
import org.webrtc.supportsLowLatency

// libwebrtc's warnings reach homebase.log through configureWebRtc; what it lacks is whether this device's H.264 decoders
// claim to sustain the 720p30 a peer may send, and whether they offer a low-latency mode.
// A lost Android camera ends webrtc-kmp's track instead, which the engine reports as capturer ended.
internal actual fun watchVideoPipeline(onCameraInterrupted: (interrupted: Boolean, detail: String) -> Unit): () -> Unit {
    avcDecoderCapabilities.forEach { Logger.i("WebRtcKmp") { it } }
    return {}
}

private val avcDecoderCapabilities: List<String> by lazy {
    runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }
            .map { describeDecoder(it) }
    }.getOrElse { listOf("avc decoders unreadable: $it") }
}

private fun describeDecoder(info: MediaCodecInfo): String {
    val video = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
    val w = SendVideoCap.WIDTH
    val h = SendVideoCap.HEIGHT
    val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated.toString() else "unknown"
    val achievable = runCatching { video.getAchievableFrameRatesFor(w, h) }.getOrNull()
    val points = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) video.supportedPerformancePoints else null
    val lowLatency = info.supportsLowLatency(MediaFormat.MIMETYPE_VIDEO_AVC) ?: "unknown"
    return "avc decoder ${info.name} sdk=${Build.VERSION.SDK_INT} hardware=$hardware lowLatency=$lowLatency max=${video.supportedWidths.upper}x${video.supportedHeights.upper} " +
        "${w}x${h}@${SendVideoCap.FPS}=${video.areSizeAndRateSupported(w, h, SendVideoCap.FPS.toDouble())} " +
        "achievableFps@${h}p=$achievable performancePoints=$points"
}
