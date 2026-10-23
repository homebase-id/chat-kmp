package id.homebase.chat.services.calling

import WebRTC.RTCVideoRendererProtocol
import WebRTC.RTCVideoTrack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import co.touchlab.kermit.Logger
import id.homebase.core.notifications.CallPictureInPictureBridgeHolder
import kotlinx.cinterop.ExperimentalForeignApi

@Composable
actual fun rememberInPictureInPicture(): Boolean = false

/**
 * Call video in a system PiP window (iOS 15+), driven by the Swift bridge. The system draws the call controls.
 * webrtc-kmp's VideoTrack reaches Swift as an opaque Kotlin object and keeps its RTCVideoTrack private: pass the receiver's,
 * and feed the camera into the inset renderer Swift hands back.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CallPictureInPictureEffect(
    eligible: Boolean,
    remote: VideoHandle?,
    local: VideoHandle?,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onHangup: () -> Unit,
    onRestore: () -> Unit,
) {
    val track = (remote as? KmpVideoHandle)?.receiver?.native?.track as? RTCVideoTrack
    val self = local as? KmpVideoHandle
    val restore by rememberUpdatedState(onRestore)
    DisposableEffect(eligible, track, self) {
        val bridge = CallPictureInPictureBridgeHolder.bridge
        if (!eligible || track == null || bridge == null) {
            Logger.i(tag = "CallPiP") { "pip not armed eligible=$eligible track=${track != null} bridge=${bridge != null}" }
            return@DisposableEffect onDispose { }
        }
        Logger.i(tag = "CallPiP") { "pip arming bridge.start self=${self != null}" }
        val selfRenderer = bridge.start(track, self != null, self?.mirrored == true, { restore() }) { Logger.i(tag = "CallPiP") { it } }
            as? RTCVideoRendererProtocol
        selfRenderer?.let { self?.track?.addRenderer(it) }
        onDispose {
            Logger.i(tag = "CallPiP") { "pip disarming bridge.stop" }
            bridge.stop()
            selfRenderer?.let { runCatching { self?.track?.removeRenderer(it) } }
        }
    }
}
