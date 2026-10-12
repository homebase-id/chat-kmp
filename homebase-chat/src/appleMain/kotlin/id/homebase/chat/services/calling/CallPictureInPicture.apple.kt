package id.homebase.chat.services.calling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import co.touchlab.kermit.Logger
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
        if (!eligible || track == null || bridge == null) {
            Logger.i("CallPiP") { "pip not armed eligible=$eligible track=${track != null} remote=${remote?.let { it::class.simpleName }} bridge=${bridge != null}" }
            return@DisposableEffect onDispose { }
        }
        Logger.i("CallPiP") { "pip arming bridge.start" }
        bridge.start(track) { Logger.i("CallPiP") { it } }
        onDispose {
            Logger.i("CallPiP") { "pip disarming bridge.stop" }
            bridge.stop()
        }
    }
}
