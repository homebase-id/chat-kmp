package id.homebase.core.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import id.homebase.resources.MR
import id.homebase.resources.camera_mode_photo
import id.homebase.resources.camera_mode_video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Where the mode labels sit, in slots (0 = first mode centred under the pill). Fractional while a finger drags them,
 * so both the carousel and a sideways swipe on the preview move the same labels.
 */
@Stable
internal class ModeCarouselState(
    initialIndex: Int,
    private val slotCount: Int,
    private val scope: CoroutineScope,
) {
    private val settled = Animatable(initialIndex.toFloat())
    val fade = Animatable(1f)
    var dragging by mutableStateOf(false)
        private set
    private var dragPosition by mutableFloatStateOf(initialIndex.toFloat())
    private var rawPosition = 0f
    private var startIndex = initialIndex
    private var tickedIndex = initialIndex
    var target = initialIndex
        private set

    val position: Float get() = if (dragging) dragPosition else settled.value

    fun dragStart(settledIndex: Int) {
        val from = position
        scope.launch { settled.stop() }
        rawPosition = from
        dragPosition = from
        startIndex = settledIndex
        tickedIndex = from.roundToInt().coerceIn(0, slotCount - 1)
        dragging = true
    }

    fun drag(deltaSlots: Float, onSlotChange: () -> Unit) {
        if (!dragging) return
        rawPosition += deltaSlots
        val last = (slotCount - 1).toFloat()
        dragPosition = when {
            rawPosition < 0f -> rawPosition * RUBBER_BAND
            rawPosition > last -> last + (rawPosition - last) * RUBBER_BAND
            else -> rawPosition
        }
        val nearest = dragPosition.roundToInt().coerceIn(0, slotCount - 1)
        if (nearest != tickedIndex) {
            tickedIndex = nearest
            onSlotChange()
        }
    }

    /** A fling past [flung] speed or a drag past half a slot moves one slot; [commit] can refuse it. */
    fun release(
        velocitySlotsPerSecond: Float,
        flung: Boolean,
        reduceMotion: Boolean,
        commit: (Int) -> Boolean,
        onSlotChange: () -> Unit,
    ) {
        if (!dragging) return
        val from = dragPosition
        val travel = from - startIndex
        val step = when {
            flung && velocitySlotsPerSecond != 0f -> sign(velocitySlotsPerSecond).toInt()
            abs(travel) > COMMIT_FRACTION -> sign(travel).toInt()
            else -> 0
        }
        var landing = (startIndex + step).coerceIn(0, slotCount - 1)
        if (landing != startIndex && !commit(landing)) landing = startIndex
        if (landing != tickedIndex) onSlotChange()
        tickedIndex = landing
        target = landing
        scope.launch {
            settled.snapTo(from)
            dragging = false
            settle(landing, velocitySlotsPerSecond, reduceMotion)
        }
    }

    fun settleTo(index: Int, reduceMotion: Boolean) {
        if (dragging || index == target) return
        target = index
        scope.launch { settle(index, 0f, reduceMotion) }
    }

    private suspend fun settle(index: Int, velocity: Float, reduceMotion: Boolean) {
        if (reduceMotion) {
            settled.snapTo(index.toFloat())
            fade.snapTo(REDUCED_FADE_FROM)
            fade.animateTo(1f, tween(REDUCED_FADE_MS))
        } else {
            settled.animateTo(index.toFloat(), SETTLE_SPRING, initialVelocity = velocity)
        }
    }

    private companion object {
        const val RUBBER_BAND = 0.35f
        const val COMMIT_FRACTION = 0.5f
        const val REDUCED_FADE_FROM = 0.3f
        const val REDUCED_FADE_MS = 150
        val SETTLE_SPRING = spring<Float>(dampingRatio = 0.8f, stiffness = 380f)
    }
}

private val CaptureMode.tag: String
    get() = when (this) {
        CaptureMode.Photo -> MODE_PHOTO_TAG
        CaptureMode.Video -> MODE_VIDEO_TAG
    }

internal class ModeSlotMetrics(val slot: Dp, val height: Dp)

@Composable
internal fun rememberModeSlotMetrics(labels: List<String>): ModeSlotMetrics {
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(labels, style, density) {
        val measured = labels.map { measurer.measure(it, style) }
        with(density) {
            ModeSlotMetrics(
                slot = max(ModeSlotMinWidth, measured.maxOf { it.size.width }.toDp() + ModeLabelPadding * 2),
                height = max(ModeSlotMinHeight, measured.maxOf { it.size.height }.toDp() + ModeLabelPadding),
            )
        }
    }
}

@Composable
internal fun modeLabel(mode: CaptureMode): String = stringResource(
    when (mode) {
        CaptureMode.Photo -> MR.string.camera_mode_photo
        CaptureMode.Video -> MR.string.camera_mode_video
    }
)

/** The selected mode sits under a fixed pill and the labels slide beneath it, following the finger. */
@Composable
internal fun ModeCarousel(
    state: ModeCarouselState,
    modes: List<CaptureMode>,
    labels: List<String>,
    metrics: ModeSlotMetrics,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (CaptureMode) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (deltaPx: Float, slotPx: Float) -> Unit,
    onDragEnd: (velocityPx: Float, slotPx: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val slotPx = with(LocalDensity.current) { metrics.slot.toPx() }
    val currentSlotPx by rememberUpdatedState(slotPx)
    Box(
        modifier = modifier
            .background(colors.scrim.copy(alpha = TRACK_ALPHA), CircleShape)
            .padding(ModeTrackInset)
            .width(metrics.slot * 3)
            .height(metrics.height)
            .clip(CircleShape)
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                var active = false
                detectHorizontalDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        active = currentEnabled
                        if (active) currentOnDragStart()
                    },
                    onDragEnd = {
                        if (active) currentOnDragEnd(tracker.calculateVelocity().x, currentSlotPx)
                        active = false
                    },
                    onDragCancel = {
                        if (active) currentOnDragEnd(0f, currentSlotPx)
                        active = false
                    },
                ) { change, dragAmount ->
                    if (!active) return@detectHorizontalDragGestures
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    currentOnDrag(dragAmount, currentSlotPx)
                }
            }
            .selectableGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(metrics.slot)
                .fillMaxHeight()
                .background(colors.primary, CircleShape),
        )
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(((1f - state.position) * slotPx).roundToInt(), 0) }
                .graphicsLayer { alpha = state.fade.value },
        ) {
            modes.forEachIndexed { index, mode ->
                val distance = abs(state.position - index).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .width(metrics.slot)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(
                            selected = index == selectedIndex,
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = { onSelect(mode) },
                        )
                        .testTag(mode.tag),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = labels[index],
                        style = MaterialTheme.typography.labelLarge,
                        color = lerp(colors.onPrimary, colors.onSurface, distance),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private const val TRACK_ALPHA = 0.4f
private val ModeSlotMinWidth = 92.dp
private val ModeSlotMinHeight = 40.dp
private val ModeLabelPadding = 16.dp
internal val ModeTrackInset = 4.dp
