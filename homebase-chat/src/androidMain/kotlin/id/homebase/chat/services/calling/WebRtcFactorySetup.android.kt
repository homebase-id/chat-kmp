package id.homebase.chat.services.calling

import android.content.pm.ApplicationInfo
import android.util.Log
import co.touchlab.kermit.Logger
import com.shepeliev.webrtckmp.WebRtc
import id.homebase.api.ActivityProvider
import org.webrtc.Loggable
import org.webrtc.Logging
import org.webrtc.lowLatencyVideoDecoderFactory

// The logger can only be injected through PeerConnectionFactory.initialize, which webrtc-kmp runs when it first builds
// the factory; once injected, Logging.enableLogToDebugOutput throws, so debug builds get their logcat copy from here.
internal val configureWebRtcFactory: Unit by lazy {
    val context = ActivityProvider.requireApplicationContext()
    val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val severity = if (debuggable) Logging.Severity.LS_INFO else Logging.Severity.LS_WARNING
    val options = WebRtc.createInitializationOptionsBuilder().setInjectableLogger(WebRtcLog, severity)
    // Texture output has one slot and dropped 1080p to 15 fps until the low-latency keys made decode fast (CALLING.md).
    val builder = WebRtc.createPeerConnectionFactoryBuilder(initializationOptionsBuilder = options)
        .setVideoDecoderFactory(lowLatencyVideoDecoderFactory(WebRtc.rootEglBase.eglBaseContext))
    WebRtc.configure(peerConnectionFactoryBuilder = builder)
    Logger.i(tag = "WebRtcKmp") { "libwebrtc logging at $severity, warnings to homebase.log; video decoders output textures in low-latency mode" }
}

private object WebRtcLog : Loggable {
    override fun onLogMessage(message: String, severity: Logging.Severity, tag: String) {
        val line = message.trimEnd()
        when (severity) {
            Logging.Severity.LS_ERROR -> Logger.e(tag = "WebRTC") { "$tag: $line" }
            Logging.Severity.LS_WARNING -> Logger.w(tag = "WebRTC") { "$tag: $line" }
            else -> Log.i(tag, line)
        }
    }
}
