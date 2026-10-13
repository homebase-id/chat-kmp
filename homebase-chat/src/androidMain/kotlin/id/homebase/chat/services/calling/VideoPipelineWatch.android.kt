package id.homebase.chat.services.calling

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import co.touchlab.kermit.Logger
import org.koin.mp.KoinPlatformTools
import org.webrtc.Logging

// Camera2 and MediaCodec already report freezes and codec errors to logcat with the WebRTC tags; what homebase.log
// lacks is whether this device's H.264 decoders claim to sustain the 1080p30 a peer may send.
internal actual fun watchVideoPipeline(): () -> Unit {
    avcDecoderCapabilities.forEach { Logger.i("WebRtcKmp") { it } }
    webRtcLogcat
    return {}
}

// AndroidVideoDecoder drops a decoded frame silently while the previous texture is still held downstream, and
// framesDropped cannot tell that from the frame buffer skipping late frames. libwebrtc's own logcat lines can:
// "no HW buffers available; decoder falling behind", the frame buffer's drop reasons and EglRenderer's render stats.
private val webRtcLogcat: Unit by lazy {
    val context = KoinPlatformTools.defaultContext().get().get<Context>()
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)
        Logger.i("WebRtcKmp") { "libwebrtc logging to logcat at LS_INFO" }
    }
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
