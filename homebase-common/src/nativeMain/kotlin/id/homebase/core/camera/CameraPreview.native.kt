@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectZero
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView

@Composable
actual fun CameraPreview(
    engine: CameraEngine,
    modifier: Modifier,
    onLongPressFocus: (Offset) -> Unit,
) {
    val iosEngine = engine as? IosCameraEngine
    if (iosEngine == null) {
        Box(modifier)
        return
    }
    LaunchedEffect(iosEngine) {
        // isPreviewing has no callback reachable from Kotlin (KVO is an NSObject category), so read it per frame.
        while (!iosEngine.previewLayer.previewing) withFrameNanos { }
        iosEngine.onPreviewShowing()
    }
    val density = LocalDensity.current.density
    val currentOnLongPressFocus by rememberUpdatedState(onLongPressFocus)
    val fade = LocalCameraFade.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(iosEngine, density) {
                fun focus(offset: Offset, lock: Boolean) =
                    iosEngine.focusAt(CGPointMake(offset.x / density.toDouble(), offset.y / density.toDouble()), offset, lock)
                detectPreviewTaps(
                    onLongPress = { offset ->
                        focus(offset, lock = true)
                        currentOnLongPressFocus(offset)
                    },
                    onTap = { offset -> focus(offset, lock = false) },
                )
            },
    ) {
        UIKitView(
            factory = { CameraPreviewView(iosEngine.previewLayer, iosEngine::applyPreviewRotation) },
            modifier = Modifier.fillMaxSize(),
            update = { it.alpha = fade().toDouble() },
            // Non-interactive so taps and pinches reach Compose instead of the UIView.
            properties = UIKitInteropProperties(interactionMode = null),
        )
    }
}

private class CameraPreviewView(
    private val previewLayer: AVCaptureVideoPreviewLayer,
    private val onLayout: () -> Unit,
) : UIView(frame = CGRectZero.readValue()) {
    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        previewLayer.frame = bounds
        CATransaction.commit()
        onLayout()
    }
}
