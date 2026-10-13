package id.homebase.chat.services.calling

import WebRTC.RTCMTLVideoView
import WebRTC.RTCVideoTrack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import co.touchlab.kermit.Logger
import id.homebase.core.notifications.CallPictureInPictureBridgeHolder
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.UIKit.UIViewContentMode

@Composable
actual fun rememberInPictureInPicture(): Boolean = false

/**
 * Call video in a system PiP window (iOS 15+), driven by the Swift bridge. The system draws the call controls.
 * webrtc-kmp's VideoTrack reaches Swift as an opaque Kotlin object and keeps its RTCVideoTrack private: pass the receiver's,
 * and render the camera here into a view Swift only has to place.
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
            Logger.i("CallPiP") { "pip not armed eligible=$eligible track=${track != null} bridge=${bridge != null}" }
            return@DisposableEffect onDispose { }
        }
        val selfView = self?.let {
            RTCMTLVideoView().apply {
                videoContentMode = UIViewContentMode.UIViewContentModeScaleAspectFill
                transform = CGAffineTransformMakeScale(if (it.mirrored) -1.0 else 1.0, 1.0)
            }
        }
        selfView?.let { self.track.addRenderer(it) }
        Logger.i("CallPiP") { "pip arming bridge.start self=${selfView != null}" }
        bridge.start(track, selfView, { restore() }) { Logger.i("CallPiP") { it } }
        onDispose {
            Logger.i("CallPiP") { "pip disarming bridge.stop" }
            bridge.stop()
            selfView?.let { runCatching { self.track.removeRenderer(it) } }
        }
    }
}
