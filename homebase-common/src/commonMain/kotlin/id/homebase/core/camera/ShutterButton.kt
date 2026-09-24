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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import kotlin.math.abs

internal const val SHUTTER_TAG = "camera_shutter"

/**
 * Tap: [CaptureButtonState.tapAction]. Hold: [onHoldStart] once the long-press timeout passes (false = the hold
 * didn't start a recording), then vertical travel above the button reports [onHoldZoom] (0..1), and travel toward
 * [lockOffsetX] reports [onLockProgress]; lifting over the lock calls [onLock], anywhere else [onHoldEnd].
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
    onHoldStart: () -> Boolean,
    onHoldZoom: (Float) -> Unit,
    onHoldEnd: () -> Unit,
    lockOffsetX: Float,
    onLockProgress: (Float) -> Unit,
    onLock: () -> Unit,
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
    val currentLockOffsetX by rememberUpdatedState(lockOffsetX)
    val currentOnLockProgress by rememberUpdatedState(onLockProgress)
    val currentOnLock by rememberUpdatedState(onLock)

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
                    onLongClick {
                        val started = currentEnabled && currentOnHoldStart()
                        if (started) currentOnLock()
                        started
                    }
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
                        var timedOut = false
                        val lifted = if (canHold) {
                            try {
                                withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                            } catch (_: PointerEventTimeoutCancellationException) {
                                timedOut = true
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
                        if (!timedOut || !currentOnHoldStart()) return@awaitEachGesture

                        val zoomTravel = size.height * HOLD_ZOOM_TRAVEL_MULTIPLIER
                        var overLock = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val drag = change.position - down.position
                            val lockX = currentLockOffsetX
                            val lockProgress =
                                if (lockX == 0f) 0f else (drag.x / lockX).coerceIn(0f, 1f)
                            val towardLock = lockProgress * abs(lockX) > abs(drag.y) && lockProgress > 0.1f
                            overLock = lockProgress >= LOCK_SNAP_FRACTION
                            currentOnLockProgress(if (towardLock || overLock) lockProgress else 0f)
                            if (!towardLock) {
                                val above = (-drag.y).coerceAtLeast(0f)
                                currentOnHoldZoom((above / zoomTravel).coerceIn(0f, 1f))
                            }
                            change.consume()
                        }
                        currentOnLockProgress(0f)
                        if (overLock) currentOnLock() else currentOnHoldEnd()
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
        // Composed only while recording: an infinite transition on an idle shutter would redraw every frame.
        if (recording) RecordingArc(Modifier.matchParentSize())
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
private const val LOCK_SNAP_FRACTION = 0.85f

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

@Composable
private fun RecordingArc(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.error
    val sweep = rememberInfiniteTransition()
    val start by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
    )
    Box(
        modifier.drawBehind {
            val stroke = 4.dp.toPx()
            drawArc(
                color = color,
                startAngle = start - 90f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        },
    )
}
