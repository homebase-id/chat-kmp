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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIAccessibilityVoiceOverStatusDidChangeNotification
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.UIKit.UIView
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectGetMidX
import platform.CoreGraphics.CGRectGetMidY
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIViewContentMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.shepeliev.webrtckmp.VideoTrack

actual fun platformCallEngineFactory(): CallEngineFactory? = CallEngineFactory { WebRtcKmpCallEngine(it) }

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CallVideoView(handle: VideoHandle?, modifier: Modifier, cornerRadius: Dp) {
    val kmp = handle as? KmpVideoHandle ?: return
    val renderers = LocalCallVideoRenderers.current
    val attached = remember(kmp.track, renderers) { renderers?.obtain(kmp) ?: AttachedVideoView(kmp) }
    if (renderers == null) {
        DisposableEffect(attached) { onDispose { attached.detach() } }
    }
    UIKitView(
        // A wrapper per interop view: a pooled video view moves into the new one, whatever order the old one is released in.
        factory = { VideoContainer().apply { addSubview(attached.view) } },
        modifier = modifier,
        // A cooperative interop view eats the tap that brings the hidden controls back.
        properties = UIKitInteropProperties(interactionMode = null),
        update = { container ->
            val video = attached.view
            if (video.superview != container) container.addSubview(video)
            container.setNeedsLayout()
            video.transform = CGAffineTransformMakeScale(if (kmp.mirrored) -1.0 else 1.0, 1.0)
            container.layer.cornerRadius = cornerRadius.value.toDouble()
            container.clipsToBounds = cornerRadius.value > 0f
        },
    )
}

// Lays the video out by bounds and centre: its mirroring transform leaves its frame undefined.
@OptIn(ExperimentalForeignApi::class)
private class VideoContainer : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    override fun layoutSubviews() {
        super.layoutSubviews()
        subviews.forEach { child ->
            (child as UIView).setBounds(bounds)
            child.setCenter(CGPointMake(CGRectGetMidX(bounds), CGRectGetMidY(bounds)))
        }
    }
}

actual class CallVideoRenderers actual constructor() {
    private val views = HashMap<VideoTrack, AttachedVideoView>()

    internal fun obtain(handle: KmpVideoHandle): AttachedVideoView = views.getOrPut(handle.track) { AttachedVideoView(handle) }

    actual fun keepOnly(handles: List<VideoHandle>) {
        val live = handles.mapNotNullTo(HashSet()) { (it as? KmpVideoHandle)?.track }
        views.keys.filterNot { it in live }.forEach { views.remove(it)?.detach() }
    }

    actual fun releaseAll() {
        views.values.forEach { it.detach() }
        views.clear()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class AttachedVideoView(handle: KmpVideoHandle) {
    private val track = handle.track
    val view = RTCMTLVideoView().apply { videoContentMode = UIViewContentMode.UIViewContentModeScaleAspectFill }

    // The view holds its delegate weakly, so this keeps it alive.
    private val sizeReporter = if (handle.local) null else RemoteSizeReporter()

    init {
        view.delegate = sizeReporter
        track.addRenderer(view)
    }

    fun detach() {
        runCatching { track.removeRenderer(view) }
        view.delegate = null
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

@Composable
actual fun rememberScreenReaderOn(): Boolean {
    var on by remember { mutableStateOf(UIAccessibilityIsVoiceOverRunning()) }
    DisposableEffect(Unit) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            UIAccessibilityVoiceOverStatusDidChangeNotification, null, null,
        ) { _ -> on = UIAccessibilityIsVoiceOverRunning() }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
    return on
}
