package id.homebase.chat.services.calling

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import co.touchlab.kermit.Logger

// Camera2 and MediaCodec already report freezes and codec errors to logcat with the WebRTC tags; what homebase.log
// lacks is whether this device's H.264 decoders claim to sustain the 1080p30 a peer may send.
internal actual fun watchVideoPipeline(): () -> Unit {
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
    return "avc decoder ${info.name} hardware=$hardware max=${video.supportedWidths.upper}x${video.supportedHeights.upper} " +
        "${w}x${h}@${SendVideoCap.FPS}=${video.areSizeAndRateSupported(w, h, SendVideoCap.FPS.toDouble())} " +
        "achievableFps@${h}p=$achievable performancePoints=$points"
}
