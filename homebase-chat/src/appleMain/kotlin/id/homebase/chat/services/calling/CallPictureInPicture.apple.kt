package id.homebase.chat.services.calling

import WebRTC.RTCVideoTrack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import co.touchlab.kermit.Logger
import id.homebase.core.notifications.CallPictureInPictureBridgeHolder
import kotlinx.cinterop.ExperimentalForeignApi

@Composable
actual fun rememberInPictureInPicture(): Boolean = false

/**
 * Remote video in a system PiP window (iOS 15+), driven by the Swift bridge. The system draws the call controls.
 * webrtc-kmp's VideoTrack reaches Swift as an opaque Kotlin object and keeps its RTCVideoTrack private: pass the receiver's.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CallPictureInPictureEffect(eligible: Boolean, remote: VideoHandle?, muted: Boolean, onToggleMute: () -> Unit, onHangup: () -> Unit) {
    val handle = remote as? KmpVideoHandle
    val native = handle?.receiver?.native?.track
    val track = native as? RTCVideoTrack
    DisposableEffect(eligible, track) {
        val bridge = CallPictureInPictureBridgeHolder.bridge
        if (!eligible || track == null || bridge == null) {
            Logger.i("CallPiP") {
                "pip not armed eligible=$eligible track=${track != null} remote=${remote?.let { it::class.simpleName }} " +
                    "receiver=${handle?.receiver != null} nativeTrack=${native?.let { it::class.simpleName }} bridge=${bridge != null}"
            }
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
