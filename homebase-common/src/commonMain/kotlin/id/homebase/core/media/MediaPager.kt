package id.homebase.core.media

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
        userScrollEnabled = userScrollEnabled,
        pageSpacing = pageSpacing,
        pageContent = { page -> pageContent(page) },
    )
}
