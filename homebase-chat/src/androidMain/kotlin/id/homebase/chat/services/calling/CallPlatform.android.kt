package id.homebase.chat.services.calling

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

actual fun platformCallEngineFactory(): CallEngineFactory? = CallEngineFactory { WebRtcKmpCallEngine(it) }

@Composable
actual fun CallVideoView(handle: VideoHandle?, modifier: Modifier) {
    val kmp = handle as? KmpVideoHandle ?: return
    val track = kmp.track
    val egl = remember { EglBase.create() }
    val renderer = remember { arrayOfNulls<SurfaceViewRenderer>(1) }
    DisposableEffect(track) {
        onDispose {
            renderer[0]?.let { track.removeSink(it); it.release() }
            renderer[0] = null
        }
    }
    DisposableEffect(Unit) { onDispose { egl.release() } }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(kmp.mirrored)
                visibility = View.VISIBLE
                track.addSink(this)
                renderer[0] = this
            }
        },
    )
}

// The "calls" notification channel rings on Android; ringing here too would double up.
@Composable
actual fun CallRingEffect(ringing: Boolean, callerName: String) = Unit

actual fun platformAudioRoutes(): List<AudioRoute> {
    val routes = mutableListOf(AudioRoute.Earpiece, AudioRoute.Speaker)
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return routes
    val audio = org.koin.mp.KoinPlatformTools.defaultContext().get().get<android.content.Context>()
        .getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    val types = audio.availableCommunicationDevices.map { it.type }
    if (types.any { it == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET }) {
        routes += AudioRoute.Bluetooth
    }
    if (types.any { it == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET || it == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it == android.media.AudioDeviceInfo.TYPE_USB_HEADSET }) {
        routes += AudioRoute.Wired
    }
    return routes
}
