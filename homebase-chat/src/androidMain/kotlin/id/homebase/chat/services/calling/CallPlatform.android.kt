package id.homebase.chat.services.calling

import id.homebase.api.ActivityProvider
import android.content.Context
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import com.shepeliev.webrtckmp.VideoTrack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
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

actual fun platformCallEngineFactory(): CallEngineFactory? = CallEngineFactory { WebRtcKmpCallEngine(it) }

@Composable
actual fun CallVideoView(handle: VideoHandle?, modifier: Modifier, cornerRadius: Dp) {
    val kmp = handle as? KmpVideoHandle ?: return
    val context = LocalContext.current
    val renderers = LocalCallVideoRenderers.current
    // A TextureView is drawn inside the hierarchy, so the caller's clip rounds it; a SurfaceView would ignore it.
    key(kmp.track, renderers) {
        val renderer = remember { renderers?.obtain(context, kmp) ?: newRenderer(context, kmp) }
        if (renderers == null) {
            DisposableEffect(Unit) { onDispose { kmp.track.detach(renderer) } }
        }
        AndroidView(
            modifier = if (cornerRadius > 0.dp) modifier.clip(RoundedCornerShape(cornerRadius)) else modifier,
            // A pooled renderer may still sit in the view it is moving from, whose release can come after this.
            factory = { renderer.also { (it.parent as? ViewGroup)?.removeView(it) } },
            update = { it.setMirror(kmp.mirrored) },
        )
    }
}

actual class CallVideoRenderers actual constructor() {
    private val renderers = HashMap<VideoTrack, TextureVideoRenderer>()

    internal fun obtain(context: Context, handle: KmpVideoHandle): TextureVideoRenderer =
        renderers.getOrPut(handle.track) { newRenderer(context, handle) }

    actual fun keepOnly(handles: List<VideoHandle>) {
        val live = handles.kmpTracks()
        renderers.keys.filterNot { it in live }.forEach { track -> renderers.remove(track)?.let { track.detach(it) } }
    }
}

// Same GL context the camera capturer uses; a private EglBase cannot sample its textures and renders black.
private fun newRenderer(context: Context, handle: KmpVideoHandle) =
    TextureVideoRenderer(context, WebRtc.rootEglBase.eglBaseContext, onFrameSize = if (handle.local) null else { w, h -> handle.frameSize.value = w to h }).apply {
        setMirror(handle.mirrored)
        handle.track.addSink(this)
    }

private fun VideoTrack.detach(renderer: TextureVideoRenderer) {
    runCatching { removeSink(renderer) }
    renderer.release()
}

actual fun audioRouteChanges(): Flow<List<AudioRoute>> = callbackFlow {
    // Registering reports every device already connected, which sends the first list.
    val unregister = onAudioDevicesChanged { trySend(availableAudioRoutes()) }
    awaitClose(unregister)
}.distinctUntilChanged()

/** Calls [changed] on the main thread whenever an audio device is added or removed; returns the unregister. */
internal fun onAudioDevicesChanged(changed: () -> Unit): () -> Unit {
    val audio = ActivityProvider.requireApplicationContext().getSystemService(AudioManager::class.java)
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
    val audio = ActivityProvider.requireApplicationContext().getSystemService(AudioManager::class.java)
    val types = audio.availableCommunicationDevices.map { it.type }
    if (types.any { it in BluetoothDeviceTypes }) {
        routes += AudioRoute.Bluetooth
    }
    if (types.any { it in WiredDeviceTypes }) {
        routes += AudioRoute.Wired
    }
    return routes
}

@Composable
actual fun rememberScreenReaderOn(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
        ?: return false
    var on by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}
