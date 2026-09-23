package id.homebase.core.camera

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph

internal const val SHUTTER_TAG = "camera_shutter"

/**
 * Tap: [CaptureButtonState.tapAction]. Hold: [onHoldStart] once the long-press timeout passes, then vertical
 * travel above the button reports [onHoldZoom] (0..1) until release calls [onHoldEnd].
 */
@Composable
internal fun ShutterButton(
    state: CaptureButtonState,
    holdEnabled: Boolean,
    busy: Boolean,
    enabled: Boolean,
    label: String,
    stateLabel: String?,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldZoom: (Float) -> Unit,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnHoldStart by rememberUpdatedState(onHoldStart)
    val currentOnHoldZoom by rememberUpdatedState(onHoldZoom)
    val currentOnHoldEnd by rememberUpdatedState(onHoldEnd)
    val currentHoldEnabled by rememberUpdatedState(holdEnabled)
    val currentEnabled by rememberUpdatedState(enabled && !busy)
    val currentState by rememberUpdatedState(state)

    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val recording = state.isRecording
    val videoLook = state != CaptureButtonState.Photo

    val pressMorph by animateFloatAsState(
        targetValue = if (pressed && !recording) 1f else 0f,
        animationSpec = motion.fastSpatialSpec(),
    )
    val recordMorph by animateFloatAsState(
        targetValue = if (state == CaptureButtonState.RecordingLocked) 1f else 0f,
        animationSpec = motion.defaultSpatialSpec(),
    )
    val innerScale by animateFloatAsState(
        targetValue = when {
            state == CaptureButtonState.RecordingLocked -> 0.5f
            state == CaptureButtonState.RecordingHeld -> 0.62f
            pressed -> 0.86f
            else -> 1f
        },
        animationSpec = motion.defaultSpatialSpec(),
    )
    val cookieSpin by animateFloatAsState(
        targetValue = if (pressed && !recording) 40f else 0f,
        animationSpec = motion.slowSpatialSpec(),
    )
    val innerColor by animateColorAsState(
        targetValue = if (videoLook) colors.error else colors.onSurface,
        animationSpec = motion.defaultEffectsSpec(),
    )
    val ringColor by animateColorAsState(
        targetValue = if (recording) colors.onSurface.copy(alpha = 0.5f) else colors.onSurface,
        animationSpec = motion.defaultEffectsSpec(),
    )

    val pressShape = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val recordShape = remember { Morph(MaterialShapes.Circle, MaterialShapes.Square) }
    val path = remember { Path() }

    Box(
        modifier = modifier
            .size(ShutterSize)
            .testTag(SHUTTER_TAG)
            .semantics {
                role = Role.Button
                contentDescription = label
                stateLabel?.let { stateDescription = it }
                onClick { if (currentEnabled) currentOnTap(); currentEnabled }
                if (holdEnabled) {
                    onLongClick { if (currentEnabled) currentOnHoldStart(); currentEnabled }
                }
            }
            .border(width = 4.dp, color = ringColor, shape = CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentEnabled) return@awaitEachGesture
                    pressed = true
                    try {
                        val canHold = currentHoldEnabled && !currentState.isRecording
                        val lifted = if (canHold) {
                            try {
                                withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                            } catch (_: PointerEventTimeoutCancellationException) {
                                null
                            }
                        } else {
                            waitForUpOrCancellation()
                        }
                        if (lifted != null) {
                            lifted.consume()
                            currentOnTap()
                            return@awaitEachGesture
                        }
                        if (!canHold) return@awaitEachGesture

                        currentOnHoldStart()
                        val travel = size.height * HOLD_ZOOM_TRAVEL_MULTIPLIER
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val above = (down.position.y - change.position.y).coerceAtLeast(0f)
                            currentOnHoldZoom((above / travel).coerceIn(0f, 1f))
                            change.consume()
                        }
                        currentOnHoldEnd()
                    } finally {
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(InnerSize)
                .graphicsLayer {
                    scaleX = innerScale
                    scaleY = innerScale
                }
                .drawBehind {
                    val morph = if (recordMorph > 0f) recordShape else pressShape
                    val progress = if (recordMorph > 0f) recordMorph else pressMorph
                    morph.toComposePath(progress, size, path)
                    rotate(if (recordMorph > 0f) 0f else cookieSpin) {
                        drawPath(path, innerColor)
                    }
                },
        )
        if (busy) {
            LoadingIndicator(
                modifier = Modifier.size(InnerSize),
                color = if (videoLook) colors.onError else colors.surface,
            )
        }
    }
}

private val ShutterSize = 80.dp
private val InnerSize = 64.dp
private const val HOLD_ZOOM_TRAVEL_MULTIPLIER = 4f

private val unitToPath = Matrix()

private fun Morph.toComposePath(progress: Float, size: Size, path: Path) {
    path.rewind()
    var first = true
    forEachCubic(progress) { cubic ->
        if (first) {
            path.moveTo(cubic.anchor0X, cubic.anchor0Y)
            first = false
        }
        path.cubicTo(
            cubic.control0X, cubic.control0Y,
            cubic.control1X, cubic.control1Y,
            cubic.anchor1X, cubic.anchor1Y,
        )
    }
    path.close()
    unitToPath.reset()
    unitToPath.scale(size.width, size.height)
    path.transform(unitToPath)
}
