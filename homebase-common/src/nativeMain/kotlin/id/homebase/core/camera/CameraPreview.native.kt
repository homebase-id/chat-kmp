@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.camera

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
actual fun CameraPreview(engine: CameraEngine, modifier: Modifier, onTapFocus: (Offset) -> Unit) {
    val iosEngine = engine as? IosCameraEngine
    if (iosEngine == null) {
        Box(modifier)
        return
    }
    val density = LocalDensity.current.density
    val currentOnTapFocus by rememberUpdatedState(onTapFocus)
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(iosEngine, density) {
                detectTapGestures { offset ->
                    iosEngine.focusAt(CGPointMake(offset.x / density.toDouble(), offset.y / density.toDouble()), offset)
                    currentOnTapFocus(offset)
                }
            },
    ) {
        UIKitView(
            factory = { CameraPreviewView(iosEngine.previewLayer, iosEngine::applyPreviewRotation) },
            modifier = Modifier.fillMaxSize(),
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
