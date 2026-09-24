package id.homebase.core.camera

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
actual fun CameraPreview(
    engine: CameraEngine,
    modifier: Modifier,
    onTapFocus: (Offset) -> Unit,
    onLongPressFocus: (Offset) -> Unit,
) {
    val androidEngine = engine as? AndroidCameraEngine
    if (androidEngine == null) {
        Box(modifier)
        return
    }
    val request by androidEngine.surfaceRequest.collectAsStateWithLifecycle()
    val currentOnTapFocus by rememberUpdatedState(onTapFocus)
    val currentOnLongPressFocus by rememberUpdatedState(onLongPressFocus)
    val surfaceRequest = request
    if (surfaceRequest == null) {
        Box(modifier)
        return
    }
    val transformer = remember { MutableCoordinateTransformer() }
    CameraXViewfinder(
        surfaceRequest = surfaceRequest,
        // A SurfaceView's layer is torn down apart from the dialog window, so a close showed black under the HUD
        // until the window left; a TextureView keeps the preview in the window's own last frame.
        implementationMode = ImplementationMode.EMBEDDED,
        coordinateTransformer = transformer,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(surfaceRequest) {
                val factory = SurfaceOrientedMeteringPointFactory(
                    surfaceRequest.resolution.width.toFloat(),
                    surfaceRequest.resolution.height.toFloat(),
                )
                fun focus(offset: Offset, lock: Boolean) {
                    val surfacePoint = with(transformer) { offset.transform() }
                    androidEngine.focusAt(factory.createPoint(surfacePoint.x, surfacePoint.y), offset, lock)
                }
                detectTapGestures(
                    onLongPress = { offset ->
                        focus(offset, lock = true)
                        currentOnLongPressFocus(offset)
                    },
                    onTap = { offset ->
                        focus(offset, lock = false)
                        currentOnTapFocus(offset)
                    },
                )
            },
    )
}
