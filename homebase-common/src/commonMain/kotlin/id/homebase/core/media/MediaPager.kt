package id.homebase.core.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun MediaPager(
    pageCount: Int,
    modifier: Modifier = Modifier,
    state: PagerState = rememberPagerState(initialPage = 0) { pageCount },
    beyondViewportPageCount: Int = 1,
    userScrollEnabled: Boolean = true,
    pageSpacing: Dp = 24.dp,
    swipeToDismiss: Boolean = false,
    swipeToDismissEnabled: Boolean = true,
    onDismiss: (() -> Unit)? = null,
    pageContent: @Composable (page: Int) -> Unit,
) {
    if (swipeToDismiss && onDismiss != null) {
        SwipeToDismissMediaPager(
            pageCount = pageCount,
            modifier = modifier,
            state = state,
            beyondViewportPageCount = beyondViewportPageCount,
            userScrollEnabled = userScrollEnabled,
            pageSpacing = pageSpacing,
            swipeToDismissEnabled = swipeToDismissEnabled,
            onDismiss = onDismiss,
            pageContent = pageContent,
        )
    } else {
        HorizontalPager(
            state = state,
            modifier = modifier.fillMaxSize(),
            beyondViewportPageCount = beyondViewportPageCount,
            userScrollEnabled = userScrollEnabled,
            pageSpacing = pageSpacing,
            pageContent = { page -> pageContent(page) },
        )
    }
}

@Composable
private fun SwipeToDismissMediaPager(
    pageCount: Int,
    modifier: Modifier,
    state: PagerState,
    beyondViewportPageCount: Int,
    userScrollEnabled: Boolean,
    pageSpacing: Dp,
    swipeToDismissEnabled: Boolean,
    onDismiss: () -> Unit,
    pageContent: @Composable (page: Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dismissOffset = remember { Animatable(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { DISMISS_THRESHOLD.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .offset { IntOffset(0, dismissOffset.value.roundToInt()) }
            .graphicsLayer {
                val progress = (abs(dismissOffset.value) / dismissThresholdPx).coerceIn(0f, 1f)
                val s = 1f - (progress * DISMISS_SCALE_REDUCTION)
                scaleX = s
                scaleY = s
            }
            .draggable(
                state = rememberDraggableState { delta ->
                    scope.launch { dismissOffset.snapTo(dismissOffset.value + delta) }
                },
                orientation = Orientation.Vertical,
                enabled = swipeToDismissEnabled,
                onDragStopped = { velocity ->
                    scope.launch {
                        if (abs(dismissOffset.value) > dismissThresholdPx || abs(velocity) > DISMISS_VELOCITY_THRESHOLD) {
                            onDismiss()
                        } else {
                            dismissOffset.animateTo(0f, spring())
                        }
                    }
                },
            ),
    ) {
        HorizontalPager(
            state = state,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = beyondViewportPageCount,
            userScrollEnabled = userScrollEnabled,
            pageSpacing = pageSpacing,
            pageContent = { page -> pageContent(page) },
        )
    }
}

private val DISMISS_THRESHOLD = 150.dp
private const val DISMISS_SCALE_REDUCTION = 0.1f
private const val DISMISS_VELOCITY_THRESHOLD = 1500f
