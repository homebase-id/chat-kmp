package id.homebase.chat.services.calling

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import co.touchlab.kermit.Logger
import com.shepeliev.webrtckmp.WebRtc
import org.koin.mp.KoinPlatformTools
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.Loggable
import org.webrtc.Logging

// The logger can only be injected through PeerConnectionFactory.initialize, which webrtc-kmp runs when it first builds
// the factory; once injected, Logging.enableLogToDebugOutput throws, so debug builds get their logcat copy from here.
internal val configureWebRtcFactory: Unit by lazy {
    val context = KoinPlatformTools.defaultContext().get().get<Context>()
    val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val severity = if (debuggable) Logging.Severity.LS_INFO else Logging.Severity.LS_WARNING
    val options = WebRtc.createInitializationOptionsBuilder().setInjectableLogger(WebRtcLog, severity)
    val builder = WebRtc.createPeerConnectionFactoryBuilder(initializationOptionsBuilder = options)
        .setVideoEncoderFactory(EvenSizeVideoEncoderFactory(DefaultVideoEncoderFactory(WebRtc.rootEglBase.eglBaseContext, true, true)))
    WebRtc.configure(peerConnectionFactoryBuilder = builder)
    Logger.i("WebRtcKmp") { "libwebrtc logging at $severity, warnings to homebase.log" }
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
