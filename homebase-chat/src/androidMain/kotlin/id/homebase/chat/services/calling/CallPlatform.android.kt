package id.homebase.chat.services.calling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shepeliev.webrtckmp.WebRtc
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink

actual fun platformCallEngineFactory(): CallEngineFactory? = CallEngineFactory { WebRtcKmpCallEngine(it) }

@Composable
actual fun CallVideoView(handle: VideoHandle?, modifier: Modifier, cornerRadius: Dp) {
    val kmp = handle as? KmpVideoHandle ?: return
    val track = kmp.track
    // Same GL context the camera capturer uses; a private EglBase cannot sample its textures and renders black.
    val eglContext = remember { WebRtc.rootEglBase.eglBaseContext }
    // A SurfaceView composites outside the view hierarchy and ignores the caller's clip; a TextureView is drawn inside it.
    val rounded = cornerRadius.value > 0f
    key(track, rounded) {
        val renderer = remember { arrayOfNulls<VideoSink>(1) }
        DisposableEffect(Unit) {
            onDispose {
                renderer[0]?.let {
                    runCatching { track.removeSink(it) }
                    when (it) {
                        is SurfaceViewRenderer -> it.release()
                        is TextureVideoRenderer -> it.release()
                    }
                }
                renderer[0] = null
            }
        }
        if (rounded) {
            AndroidView(
                modifier = modifier,
                factory = { context ->
                    TextureVideoRenderer(context, eglContext).apply {
                        setMirror(kmp.mirrored)
                        track.addSink(this)
                        renderer[0] = this
                    }
                },
                update = { it.setMirror(kmp.mirrored) },
            )
        } else {
            AndroidView(
                modifier = modifier,
                factory = { context ->
                    SurfaceViewRenderer(context).apply {
                        init(eglContext, if (kmp.local) null else remoteSizeEvents())
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setEnableHardwareScaler(true)
                        setMirror(kmp.mirrored)
                        // Two SurfaceViews stack in tree order; without the overlay flag the full-screen remote covers the PiP.
                        setZOrderMediaOverlay(kmp.local)
                        track.addSink(this)
                        renderer[0] = this
                    }
                },
                update = { it.setMirror(kmp.mirrored) },
            )
        }
    }
}

private fun remoteSizeEvents() = object : RendererCommon.RendererEvents {
    override fun onFirstFrameRendered() = Unit

    override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
        val rotated = rotation % 180 != 0
        RemoteVideoSize.current.value = if (rotated) videoHeight to videoWidth else videoWidth to videoHeight
    }
}

actual fun platformAudioRoutes(): List<AudioRoute> {
    val routes = mutableListOf(AudioRoute.Earpiece, AudioRoute.Speaker)
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return routes
    val audio = callAppContext.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    val types = audio.availableCommunicationDevices.map { it.type }
    if (types.any { it in BluetoothDeviceTypes }) {
        routes += AudioRoute.Bluetooth
    }
    if (types.any { it in WiredDeviceTypes }) {
        routes += AudioRoute.Wired
    }
    return routes
}
