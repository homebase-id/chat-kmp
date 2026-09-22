package id.homebase.chat.widget

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import id.homebase.core.haptics.HapticEvent
import id.homebase.core.haptics.rememberHaptics
import id.homebase.core.util.isMobile
import kotlinx.coroutines.delay
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val SLIDE_OUT_MS = 180

/** How long a slid-out row waits for the list to drop it before deciding the action didn't. */
private const val SLIDE_OUT_RECOVERY_MS = 700L

@Immutable
sealed interface SwipeDistance {
    data class Fixed(val distance: Dp) : SwipeDistance
    data class Fraction(val fraction: Float) : SwipeDistance
}

private fun SwipeDistance.toPx(widthPx: Int, density: Density): Float = when (this) {
    is SwipeDistance.Fixed -> with(density) { distance.toPx() }
    is SwipeDistance.Fraction -> widthPx * fraction
}

@Immutable
data class SwipeRevealState(
    /** Physical: positive means the row moved right on screen, under RTL too. */
    val offsetPx: Float,
    val thresholdPx: Float,
) {
    val progress: Float
        get() = if (thresholdPx <= 0f) 0f else (offsetPx.absoluteValue / thresholdPx).coerceIn(0f, 1f)

    val isPastThreshold: Boolean
        get() = thresholdPx > 0f && offsetPx.absoluteValue >= thresholdPx
}

/**
 * Horizontal swipe that reveals an action behind the row and fires it on release. It never
 * latches open: the row either springs back, or — where the action removes it — slides out.
 *
 * The gesture stays in physical screen space, RTL included: [onSwipeRight] / [onSwipeLeft]
 * and [SwipeRevealState.offsetPx] are pointer directions, not layout-relative ones, so a
 * caller that wants the actions to mirror under RTL swaps them itself.
 */
@Composable
fun SwipeRevealBox(
    onSwipeRight: (() -> Unit)?,
    onSwipeLeft: (() -> Unit)?,
    commitThreshold: SwipeDistance,
    maxOffset: SwipeDistance,
    modifier: Modifier = Modifier,
    enabled: Boolean = isMobile(),
    /** Null disables the flick shortcut, leaving distance as the only way to commit. */
    escapeVelocityDpPerSecond: Float? = null,
    /** Set where a committed swipe removes the row: it then slides out instead of springing
     *  back, and returns only if the row is somehow still here afterwards. */
    slideOutOnSwipeRight: Boolean = false,
    slideOutOnSwipeLeft: Boolean = false,
    reveal: @Composable BoxScope.(SwipeRevealState) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled || (onSwipeRight == null && onSwipeLeft == null)) {
        Box(modifier) { content() }
        return
    }

    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }
    val thresholdPx = commitThreshold.toPx(widthPx, density)
    val maxOffsetPx = maxOffset.toPx(widthPx, density)
    val escapeVelocityPx = escapeVelocityDpPerSecond?.let { with(density) { it.dp.toPx() } }

    // Read live inside the gesture instead of keying pointerInput on them: the gesture
    // captures its first composition, when the row is still unmeasured, and re-keying on
    // the lambdas (a new identity on every recomposition of the row) would cancel an
    // in-flight swipe every time a new message landed in that conversation.
    val currentOnSwipeRight by rememberUpdatedState(onSwipeRight)
    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentThresholdPx by rememberUpdatedState(thresholdPx)
    val currentMaxOffsetPx by rememberUpdatedState(maxOffsetPx)
    val currentEscapeVelocityPx by rememberUpdatedState(escapeVelocityPx)
    val currentSlideOutRight by rememberUpdatedState(slideOutOnSwipeRight)
    val currentSlideOutLeft by rememberUpdatedState(slideOutOnSwipeLeft)

    // Plain float state during the drag — no coroutine per pointer event. The release
    // animation writes back into the same value so the row never jumps between the two.
    var offsetPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var slideOutDirection by remember { mutableIntStateOf(0) }
    val settle = remember { Animatable(0f) }
    val haptics = rememberHaptics()
    var hapticFired by remember { mutableStateOf(false) }

    LaunchedEffect(isDragging, slideOutDirection) {
        if (isDragging) return@LaunchedEffect
        settle.snapTo(offsetPx)
        if (slideOutDirection != 0 && widthPx > 0) {
            settle.animateTo(
                targetValue = slideOutDirection * widthPx.toFloat(),
                animationSpec = tween(SLIDE_OUT_MS, easing = FastOutLinearInEasing),
            ) { offsetPx = value }
            delay(SLIDE_OUT_RECOVERY_MS)
            slideOutDirection = 0
        } else {
            settle.animateTo(0f, spring()) { offsetPx = value }
        }
    }

    Box(modifier = modifier.onSizeChanged { widthPx = it.width }) {
        if (offsetPx != 0f) {
            reveal(SwipeRevealState(offsetPx = offsetPx, thresholdPx = thresholdPx))
        }

        Box(
            modifier = Modifier
                .absoluteOffset { IntOffset(offsetPx.roundToInt(), 0) }
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val velocityTracker = VelocityTracker()

                        var totalX = 0f
                        var totalY = 0f
                        var directionDecided = false
                        var isHorizontal = false

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break

                                val delta = change.positionChange()

                                if (!directionDecided) {
                                    totalX += delta.x
                                    totalY += delta.y
                                    val distance = sqrt(totalX * totalX + totalY * totalY)
                                    if (distance > slop) {
                                        directionDecided = true
                                        // Commit to horizontal only when the drag is clearly
                                        // sideways, so the LazyColumn keeps vertical scrolling.
                                        isHorizontal =
                                            totalX.absoluteValue > totalY.absoluteValue * 1.5f
                                        if (!isHorizontal) break
                                        isDragging = true
                                        hapticFired = false
                                        slideOutDirection = 0
                                        offsetPx = 0f
                                        change.consume()
                                    }
                                    continue
                                }

                                change.consume()
                                velocityTracker.addPointerInputChange(change)
                                var newValue = offsetPx + delta.x
                                if (currentOnSwipeRight == null) newValue = newValue.coerceAtMost(0f)
                                if (currentOnSwipeLeft == null) newValue = newValue.coerceAtLeast(0f)
                                offsetPx = newValue.coerceIn(-currentMaxOffsetPx, currentMaxOffsetPx)

                                if (!hapticFired && offsetPx.absoluteValue >= currentThresholdPx) {
                                    hapticFired = true
                                    haptics.perform(HapticEvent.LongPress)
                                } else if (hapticFired && offsetPx.absoluteValue < currentThresholdPx) {
                                    hapticFired = false
                                }
                            }

                            if (isHorizontal) {
                                val commit = shouldCommitSwipe(
                                    offsetPx = offsetPx,
                                    thresholdPx = currentThresholdPx,
                                    velocityPxPerSecond = velocityTracker.calculateVelocity().x,
                                    escapeVelocityPxPerSecond = currentEscapeVelocityPx,
                                )
                                // Act on release rather than after the spring-back, so the
                                // action never trails the gesture by an animation.
                                if (commit) {
                                    val toRight = offsetPx > 0f
                                    if (toRight) currentOnSwipeRight?.invoke()
                                    else currentOnSwipeLeft?.invoke()
                                    val slidesOut =
                                        if (toRight) currentSlideOutRight else currentSlideOutLeft
                                    if (slidesOut) slideOutDirection = if (toRight) 1 else -1
                                }
                            }
                        } finally {
                            // awaitEachGesture swallows a gesture-level cancellation and
                            // restarts, so without this the row stays held open: the
                            // spring-back only runs on an isDragging edge.
                            if (isHorizontal) isDragging = false
                        }
                    }
                }
        ) {
            content()
        }
    }
}

/**
 * Whether a released swipe fires its action. Distance is the primary path; the flick
 * shortcut deliberately needs a fast, same-direction gesture that already travelled half
 * the commit distance, so a stray twitch can't trigger an action.
 */
internal fun shouldCommitSwipe(
    offsetPx: Float,
    thresholdPx: Float,
    velocityPxPerSecond: Float,
    escapeVelocityPxPerSecond: Float?,
): Boolean {
    if (thresholdPx <= 0f || offsetPx == 0f) return false
    if (offsetPx.absoluteValue >= thresholdPx) return true
    if (escapeVelocityPxPerSecond == null) return false
    return velocityPxPerSecond.absoluteValue >= escapeVelocityPxPerSecond &&
        (velocityPxPerSecond > 0f) == (offsetPx > 0f) &&
        offsetPx.absoluteValue >= thresholdPx / 2f
}
