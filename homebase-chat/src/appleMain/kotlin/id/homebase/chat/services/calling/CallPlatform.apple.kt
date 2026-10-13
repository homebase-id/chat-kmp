package id.homebase.chat.services.calling

import WebRTC.RTCMTLVideoView
import WebRTC.RTCVideoRendererProtocol
import WebRTC.RTCVideoViewDelegateProtocol
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGSize
import platform.darwin.NSObject
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.UIKit.UIViewContentMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

actual fun platformCallEngineFactory(): CallEngineFactory? = CallEngineFactory { WebRtcKmpCallEngine(it) }

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CallVideoView(handle: VideoHandle?, modifier: Modifier, cornerRadius: Dp) {
    val kmp = handle as? KmpVideoHandle ?: return
    val track = kmp.track
    // UIKitView runs its factory once, so a new track needs a new UIKitView, not just a new remembered view.
    key(track) {
        val view = remember { RTCMTLVideoView().apply { videoContentMode = UIViewContentMode.UIViewContentModeScaleAspectFill } }
        // The view holds its delegate weakly, so the composition keeps it alive.
        val sizeReporter = remember { if (kmp.local) null else RemoteSizeReporter() }
        DisposableEffect(track) {
            view.delegate = sizeReporter
            track.addRenderer(view)
            onDispose {
                runCatching { track.removeRenderer(view) }
                view.delegate = null
            }
        }
        UIKitView(
            factory = { view },
            modifier = modifier,
            update = {
                it.transform = CGAffineTransformMakeScale(if (kmp.mirrored) -1.0 else 1.0, 1.0)
                it.layer.cornerRadius = cornerRadius.value.toDouble()
                it.clipsToBounds = cornerRadius.value > 0f
            },
        )
    }
}

actual fun platformAudioRoutes(): List<AudioRoute> = listOf(AudioRoute.Earpiece, AudioRoute.Speaker)

// RTCMTLVideoView calls this on the main queue only when the rotation-applied frame size changes, not per frame.
@OptIn(ExperimentalForeignApi::class)
private class RemoteSizeReporter : NSObject(), RTCVideoViewDelegateProtocol {
    override fun videoView(videoView: RTCVideoRendererProtocol, didChangeVideoSize: CValue<CGSize>) {
        didChangeVideoSize.useContents {
            if (width > 0 && height > 0) RemoteVideoSize.current.value = width.toInt() to height.toInt()
        }
    }
}
