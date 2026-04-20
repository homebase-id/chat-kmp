package id.homebase.chat.widget

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import id.homebase.core.util.isMobile
import id.homebase.resources.MR
import id.homebase.resources.chat_message_reply
import id.homebase.resources.info
import org.jetbrains.compose.resources.stringResource
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun SwipeableMessageWrapper(
    onSwipeRight: (() -> Unit)?,
    onSwipeLeft: (() -> Unit)?,
    enabled: Boolean = isMobile(),
    content: @Composable () -> Unit,
) {
    if (!enabled || (onSwipeRight == null && onSwipeLeft == null)) {
        content()
        return
    }

    val density = LocalDensity.current
    val thresholdPx = with(density) { 72.dp.toPx() }
    val maxOffsetPx = with(density) { 100.dp.toPx() }
    // Use plain float state during drag (no coroutines needed), Animatable only for spring-back
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val animatable = remember { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    var hapticFired by remember { mutableStateOf(false) }

    // The displayed offset: drag value while dragging, animated value while springing back
    val displayOffset = if (isDragging) dragOffset else animatable.value

    // Spring back to 0 when drag ends
    LaunchedEffect(isDragging) {
        if (!isDragging) {
            animatable.snapTo(dragOffset)
            animatable.animateTo(0f, spring())
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Reply icon (left side, revealed on swipe right)
        if (onSwipeRight != null && displayOffset > 0f) {
            val progress = (displayOffset / thresholdPx).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(32.dp)
                    .scale(0.5f + 0.5f * progress)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = stringResource(MR.string.chat_message_reply),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Info icon (right side, revealed on swipe left)
        if (onSwipeLeft != null && displayOffset < 0f) {
            val progress = (displayOffset.absoluteValue / thresholdPx).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .size(32.dp)
                    .scale(0.5f + 0.5f * progress)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(MR.string.info),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Message content
        Box(
            modifier = Modifier
                .offset { IntOffset(displayOffset.roundToInt(), 0) }
                .pointerInput(onSwipeRight, onSwipeLeft) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)

                        var totalX = 0f
                        var totalY = 0f
                        var directionDecided = false
                        var isHorizontal = false

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
                                    isHorizontal =
                                        totalX.absoluteValue > totalY.absoluteValue * 1.5f
                                    if (!isHorizontal) break
                                    isDragging = true
                                    hapticFired = false
                                    dragOffset = 0f
                                    change.consume()
                                }
                                continue
                            }

                            change.consume()
                            var newValue = dragOffset + delta.x
                            // Clamp: only allow right if onSwipeRight exists, left if onSwipeLeft exists
                            if (onSwipeRight == null) newValue = newValue.coerceAtMost(0f)
                            if (onSwipeLeft == null) newValue = newValue.coerceAtLeast(0f)
                            newValue = newValue.coerceIn(-maxOffsetPx, maxOffsetPx)
                            dragOffset = newValue

                            // Fire haptic at threshold
                            if (!hapticFired && newValue.absoluteValue >= thresholdPx) {
                                hapticFired = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            } else if (hapticFired && newValue.absoluteValue < thresholdPx) {
                                hapticFired = false
                            }
                        }

                        if (isHorizontal) {
                            if (dragOffset > thresholdPx && onSwipeRight != null) {
                                onSwipeRight()
                            } else if (dragOffset < -thresholdPx && onSwipeLeft != null) {
                                onSwipeLeft()
                            }
                            isDragging = false
                        }
                    }
                }
        ) {
            content()
        }
    }
}
