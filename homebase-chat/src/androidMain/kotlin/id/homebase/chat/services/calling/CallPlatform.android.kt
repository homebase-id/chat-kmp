package id.homebase.chat.services.calling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
                        init(eglContext, if (kmp.local) null else frameSizeEvents(kmp))
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

private fun frameSizeEvents(handle: KmpVideoHandle) = object : RendererCommon.RendererEvents {
    override fun onFirstFrameRendered() = Unit

    override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
        val rotated = rotation % 180 != 0
        handle.frameSize.value = if (rotated) videoHeight to videoWidth else videoWidth to videoHeight
    }
}

actual fun audioRouteChanges(): Flow<List<AudioRoute>> = callbackFlow {
    // Registering reports every device already connected, which sends the first list.
    val unregister = onAudioDevicesChanged { trySend(availableAudioRoutes()) }
    awaitClose(unregister)
}.distinctUntilChanged()

/** Calls [changed] on the main thread whenever an audio device is added or removed; returns the unregister. */
internal fun onAudioDevicesChanged(changed: () -> Unit): () -> Unit {
    val audio = callAppContext.getSystemService(AudioManager::class.java)
    val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = changed()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = changed()
    }
    audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    return { audio.unregisterAudioDeviceCallback(callback) }
}

internal fun availableAudioRoutes(): List<AudioRoute> {
    val routes = mutableListOf(AudioRoute.Earpiece, AudioRoute.Speaker)
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return routes
    val audio = callAppContext.getSystemService(AudioManager::class.java)
    val types = audio.availableCommunicationDevices.map { it.type }
    if (types.any { it in BluetoothDeviceTypes }) {
        routes += AudioRoute.Bluetooth
    }
    if (types.any { it in WiredDeviceTypes }) {
        routes += AudioRoute.Wired
    }
    return routes
}
