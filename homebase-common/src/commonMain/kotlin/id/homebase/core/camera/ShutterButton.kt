package id.homebase.core.camera

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.coroutines.launch
import kotlin.math.abs

internal const val SHUTTER_TAG = "camera_shutter"

// Crossing LOCK_SNAP_FRACTION of the way to lockOffset locks at once, not on release.
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
    lockOffset: Offset,
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
    val currentLockOffset by rememberUpdatedState(lockOffset)
    val currentOnLockProgress by rememberUpdatedState(onLockProgress)
    val currentOnLock by rememberUpdatedState(onLock)

    val colors = MaterialTheme.colorScheme
    val record = HomebaseTheme.extendedColors.cameraRecord
    val onRecord = HomebaseTheme.extendedColors.onCameraRecord
    val motion = MaterialTheme.motionScheme
    val reduceMotion = LocalReduceMotion.current
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
        targetValue = when (state) {
            CaptureButtonState.RecordingLocked -> if (pressed) 0.44f else 0.5f
            CaptureButtonState.RecordingHeld -> 0.62f
            else -> if (pressed) 0.86f else 1f
        },
        animationSpec = motion.defaultSpatialSpec(),
    )
    val ringScale by animateFloatAsState(
        targetValue = if (state == CaptureButtonState.RecordingHeld) HELD_RING_SCALE else 1f,
        animationSpec = if (reduceMotion) snap() else motion.defaultSpatialSpec(),
    )
    val cookieSpin by animateFloatAsState(
        targetValue = if (pressed && !recording && !reduceMotion) 40f else 0f,
        animationSpec = motion.slowSpatialSpec(),
    )
    val innerColor by animateColorAsState(
        targetValue = if (videoLook) record else colors.onSurface,
        animationSpec = motion.defaultEffectsSpec(),
    )
    val ringColor by animateColorAsState(
        targetValue = if (recording) colors.onSurface.copy(alpha = 0.5f) else colors.onSurface,
        animationSpec = motion.defaultEffectsSpec(),
    )

    val pressShape = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val recordShape = remember { Morph(MaterialShapes.Circle, MaterialShapes.Square) }
    val path = remember { Path() }

    val scope = rememberCoroutineScope()
    var following by remember { mutableStateOf(false) }
    var followOffset by remember { mutableStateOf(Offset.Zero) }
    val returnOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    fun releaseFollow() {
        val from = followOffset
        scope.launch {
            returnOffset.snapTo(from)
            following = false
            if (reduceMotion) returnOffset.snapTo(Offset.Zero) else returnOffset.animateTo(Offset.Zero, FOLLOW_SPRING)
        }
    }

    Box(
        modifier = modifier
            .size(ShutterSize)
            .testTag(SHUTTER_TAG)
            .semantics {
                role = Role.Button
                contentDescription = label
                stateLabel?.let { stateDescription = it }
                onClick { if (currentEnabled) currentOnTap(); currentEnabled }
                if (state.longPressAction(holdEnabled) != null) {
                    onLongClick {
                        val started = currentEnabled && currentOnHoldStart()
                        if (started) currentOnLock()
                        started
                    }
                }
            }
            .drawBehind {
                val stroke = RingStroke.toPx()
                drawCircle(
                    color = ringColor,
                    radius = (size.minDimension - stroke) / 2 * ringScale,
                    style = Stroke(width = stroke),
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentEnabled) return@awaitEachGesture
                    pressed = true
                    try {
                        val canHold = currentState.longPressAction(currentHoldEnabled) != null
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
                        followOffset = Offset.Zero
                        following = true
                        var locked = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            if (locked) continue
                            val drag = change.position - down.position
                            val lock = currentLockOffset
                            val length = lock.getDistance()
                            if (length == 0f) {
                                currentOnHoldZoom(((-drag.y).coerceAtLeast(0f) / zoomTravel).coerceIn(0f, 1f))
                                continue
                            }
                            val unit = lock / length
                            val along = drag.x * unit.x + drag.y * unit.y
                            val across = abs(drag.x * unit.y - drag.y * unit.x)
                            val progress = (along / length).coerceIn(0f, 1f)
                            val towardLock = along > across && progress > LOCK_INTENT_FRACTION
                            followOffset = unit * along.coerceIn(0f, length)
                            currentOnLockProgress(if (towardLock) progress else 0f)
                            if (progress >= LOCK_SNAP_FRACTION) {
                                locked = true
                                currentOnLock()
                                releaseFollow()
                            } else if (!towardLock) {
                                val above = (-drag.y).coerceAtLeast(0f)
                                currentOnHoldZoom((above / zoomTravel).coerceIn(0f, 1f))
                            }
                        }
                        if (!locked) {
                            currentOnLockProgress(0f)
                            releaseFollow()
                            currentOnHoldEnd()
                        }
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
                    val offset = if (following) followOffset else returnOffset.value
                    translationX = offset.x
                    translationY = offset.y
                    scaleX = innerScale
                    scaleY = innerScale
                }
                .drawBehind {
                    val morph = if (recordMorph > 0f) recordShape else pressShape
                    val progress = if (recordMorph > 0f) recordMorph else pressMorph
                    // At rest both morphs are a circle; a circle draws far cheaper than an anti-aliased cubic path.
                    if (progress == 0f) {
                        drawCircle(innerColor)
                        return@drawBehind
                    }
                    morph.toComposePath(progress, size, path)
                    rotate(if (recordMorph > 0f) 0f else cookieSpin) {
                        drawPath(path, innerColor)
                    }
                },
        )
        // Composed only while recording: an infinite transition on an idle shutter would redraw every frame.
        if (recording) RecordingArc(color = record, ringScale = { ringScale }, still = reduceMotion, modifier = Modifier.matchParentSize())
        if (busy) {
            LoadingIndicator(
                modifier = Modifier.size(InnerSize),
                color = if (videoLook) onRecord else colors.surface,
            )
        }
    }
}

internal val ShutterSize = 80.dp
private val InnerSize = 64.dp
private val RingStroke = 4.dp
private const val HELD_RING_SCALE = 1.2f
private const val HOLD_ZOOM_TRAVEL_MULTIPLIER = 4f
private const val LOCK_INTENT_FRACTION = 0.1f
internal const val LOCK_SNAP_FRACTION = 0.85f
private val FOLLOW_SPRING = spring(dampingRatio = 0.7f, stiffness = 500f, visibilityThreshold = Offset(0.5f, 0.5f))

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
private fun RecordingArc(color: Color, ringScale: () -> Float, still: Boolean, modifier: Modifier) {
    val angle = if (still) {
        null
    } else {
        rememberInfiniteTransition().animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
        )
    }
    Box(
        modifier.drawBehind {
            val stroke = RingStroke.toPx()
            val diameter = (size.minDimension - stroke) * ringScale()
            drawArc(
                color = color,
                startAngle = (angle?.value ?: 0f) - 90f,
                sweepAngle = ARC_SWEEP,
                useCenter = false,
                topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2),
                size = Size(diameter, diameter),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        },
    )
}

private const val ARC_SWEEP = 110f
