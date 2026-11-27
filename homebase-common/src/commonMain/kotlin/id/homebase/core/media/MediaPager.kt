package id.homebase.core.media

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun MediaPager(
    pageCount: Int,
    modifier: Modifier = Modifier,
    state: PagerState = rememberPagerState(initialPage = 0) { pageCount },
    beyondViewportPageCount: Int = 1,
    userScrollEnabled: Boolean = true,
    pageSpacing: Dp = 24.dp,
    pageContent: @Composable (page: Int) -> Unit,
) {
    HorizontalPager(
        state = state,
        modifier = modifier.fillMaxSize(),
        beyondViewportPageCount = beyondViewportPageCount,
        // A one-page pager can't move, yet its snap would swallow the fling of a pager around it.
        userScrollEnabled = userScrollEnabled && state.pageCount > 1,
        pageSpacing = pageSpacing,
        pageContent = { page -> pageContent(page) },
    )
}

internal fun KeyEvent.pagerArrowStep(layoutDirection: LayoutDirection): Int {
    if (type != KeyEventType.KeyDown || isCtrlPressed || isMetaPressed || isAltPressed || isShiftPressed) return 0
    val step = when (key) {
        Key.DirectionRight -> 1
        Key.DirectionLeft -> -1
        else -> return 0
    }
    return if (layoutDirection == LayoutDirection.Rtl) -step else step
}

@Composable
fun Modifier.pagerArrowKeys(state: PagerState): Modifier {
    val scope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current
    return onKeyEvent { event ->
        val step = event.pagerArrowStep(layoutDirection)
        val target = state.targetPage + step
        if (step == 0 || target !in 0 until state.pageCount) return@onKeyEvent false
        scope.launch { state.animateScrollToPage(target) }
        true
    }
}
