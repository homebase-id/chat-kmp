package id.homebase.chat.services.calling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import id.homebase.core.notifications.CallPictureInPictureBridgeHolder

@Composable
actual fun rememberInPictureInPicture(): Boolean = false

/**
 * Remote video in a system PiP window (iOS 15+), driven by the Swift bridge. The system draws the call controls.
 * The local camera only keeps capturing in the background with the
 * `com.apple.developer.avfoundation.multitasking-camera-access` entitlement, which this build does not declare:
 * without it the remote video and audio keep going and the outgoing video freezes.
 */
@Composable
actual fun CallPictureInPictureEffect(eligible: Boolean, remote: VideoHandle?, muted: Boolean, onToggleMute: () -> Unit, onHangup: () -> Unit) {
    val track = (remote as? KmpVideoHandle)?.track
    DisposableEffect(eligible, track) {
        val bridge = CallPictureInPictureBridgeHolder.bridge
        if (!eligible || track == null || bridge == null) return@DisposableEffect onDispose { }
        bridge.start(track)
        onDispose { bridge.stop() }
    }
}
