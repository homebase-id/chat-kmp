package id.homebase.core.widget

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun BoxScope.HomebaseVerticalScrollbar(
    modifier: Modifier,
    state: LazyGridState
) {
    VerticalScrollbar(
        modifier = modifier,
        adapter = rememberScrollbarAdapter(state)
    )
}

@Composable
actual fun BoxScope.HomebaseVerticalScrollbar(
    modifier: Modifier,
    state: LazyListState
) {
    VerticalScrollbar(
        modifier = modifier,
        adapter = rememberScrollbarAdapter(state)
    )
}

@Composable
actual fun BoxScope.HomebaseVerticalScrollbar(
    modifier: Modifier,
    state: ScrollState
) {
    VerticalScrollbar(
        modifier = modifier,
        adapter = rememberScrollbarAdapter(state)
    )
}