package id.homebase.chat.services.calling

import android.content.pm.ApplicationInfo
import android.util.Log
import co.touchlab.kermit.Logger
import com.shepeliev.webrtckmp.WebRtc
import org.webrtc.Loggable
import org.webrtc.Logging
import org.webrtc.lowLatencyVideoDecoderFactory

// The logger can only be injected through PeerConnectionFactory.initialize, which webrtc-kmp runs when it first builds
// the factory; once injected, Logging.enableLogToDebugOutput throws, so debug builds get their logcat copy from here.
internal val configureWebRtcFactory: Unit by lazy {
    val context = callAppContext
    val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val severity = if (debuggable) Logging.Severity.LS_INFO else Logging.Severity.LS_WARNING
    val options = WebRtc.createInitializationOptionsBuilder().setInjectableLogger(WebRtcLog, severity)
    // A texture-mode decoder has one output texture and drops every frame decoded while libwebrtc's render queue still
    // holds the last one until its render time, which halved 1080p to 15 fps on the Redmi; byte buffers have no such slot.
    val builder = WebRtc.createPeerConnectionFactoryBuilder(initializationOptionsBuilder = options)
        .setVideoDecoderFactory(lowLatencyVideoDecoderFactory())
    WebRtc.configure(peerConnectionFactoryBuilder = builder)
    Logger.i("WebRtcKmp") { "libwebrtc logging at $severity, warnings to homebase.log; video decoders output byte buffers in low-latency mode" }
}

private object WebRtcLog : Loggable {
    override fun onLogMessage(message: String, severity: Logging.Severity, tag: String) {
        val line = message.trimEnd()
        when (severity) {
            Logging.Severity.LS_ERROR -> Logger.e("WebRTC") { "$tag: $line" }
            Logging.Severity.LS_WARNING -> Logger.w("WebRTC") { "$tag: $line" }
            else -> Log.i(tag, line)
        }
    }
}
