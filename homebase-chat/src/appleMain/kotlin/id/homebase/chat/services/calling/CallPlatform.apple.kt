package id.homebase.chat.services.calling

import WebRTC.RTCMTLVideoView
import WebRTC.RTCVideoFrame
import WebRTC.RTCVideoRendererProtocol
import co.touchlab.kermit.Logger
import kotlinx.cinterop.CValue
import platform.CoreGraphics.CGSize
import platform.UIKit.UIDevice
import platform.darwin.NSObject
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.UIKit.UIViewContentMode
import androidx.compose.ui.Modifier

actual fun platformCallEngineFactory(): CallEngineFactory? = CallEngineFactory { WebRtcKmpCallEngine(it) }

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CallVideoView(handle: VideoHandle?, modifier: Modifier) {
    val kmp = handle as? KmpVideoHandle ?: return
    val track = kmp.track
    val view = remember(track) { RTCMTLVideoView().apply { videoContentMode = UIViewContentMode.UIViewContentModeScaleAspectFill } }
    DisposableEffect(track) {
        val probe = FrameRotationProbe(if (kmp.local) "local" else "remote")
        track.addRenderer(view)
        track.addRenderer(probe)
        onDispose {
            runCatching { track.removeRenderer(view) }
            runCatching { track.removeRenderer(probe) }
        }
    }
    UIKitView(
        factory = { view },
        modifier = modifier,
        update = { it.transform = CGAffineTransformMakeScale(if (kmp.mirrored) -1.0 else 1.0, 1.0) },
    )
}

// CallKit rings on iOS.
@Composable
actual fun CallRingEffect(ringing: Boolean, callerName: String) = Unit

actual fun platformAudioRoutes(): List<AudioRoute> = listOf(AudioRoute.Earpiece, AudioRoute.Speaker)

// Frames carry the capturer's rotation; a sideways picture is either rotation=0 on a landscape buffer or a renderer ignoring it.
@OptIn(ExperimentalForeignApi::class)
private class FrameRotationProbe(private val side: String) : NSObject(), RTCVideoRendererProtocol {
    private var last = ""

    override fun renderFrame(frame: RTCVideoFrame?) {
        frame ?: return
        val seen = "rotation=${frame.rotation} buffer=${frame.width}x${frame.height}"
        if (seen == last) return
        last = seen
        Logger.i("WebRtcKmp") { "$side frame $seen deviceOrientation=${UIDevice.currentDevice.orientation}" }
    }

    override fun setSize(size: CValue<CGSize>) = Unit
}
