package id.homebase.chat.services.calling

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
import com.shepeliev.webrtckmp.WebRtc
import id.homebase.api.ActivityProvider

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
        val live = handles.mapNotNullTo(HashSet()) { (it as? KmpVideoHandle)?.track }
        renderers.keys.filterNot { it in live }.forEach { track -> renderers.remove(track)?.let { track.detach(it) } }
    }
}

// Same GL context the camera capturer uses; a private EglBase cannot sample its textures and renders black.
private fun newRenderer(context: Context, handle: KmpVideoHandle) =
    TextureVideoRenderer(context, WebRtc.rootEglBase.eglBaseContext, onFrameSize = if (handle.local) null else ::reportRemoteSize).apply {
        setMirror(handle.mirrored)
        handle.track.addSink(this)
    }

private fun VideoTrack.detach(renderer: TextureVideoRenderer) {
    runCatching { removeSink(renderer) }
    renderer.release()
}

private fun reportRemoteSize(width: Int, height: Int) {
    RemoteVideoSize.current.value = width to height
}

actual fun platformAudioRoutes(): List<AudioRoute> {
    val routes = mutableListOf(AudioRoute.Earpiece, AudioRoute.Speaker)
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return routes
    val audio = ActivityProvider.requireApplicationContext().getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
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
