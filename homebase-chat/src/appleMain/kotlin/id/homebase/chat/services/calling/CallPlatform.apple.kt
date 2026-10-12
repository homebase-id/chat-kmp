package id.homebase.chat.services.calling

import WebRTC.RTCMTLVideoView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
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
        track.addRenderer(view)
        onDispose { track.removeRenderer(view) }
    }
    UIKitView(factory = { view }, modifier = modifier, update = {})
}

// CallKit rings on iOS.
@Composable
actual fun CallRingEffect(ringing: Boolean, callerName: String) = Unit
