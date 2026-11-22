package id.homebase.core.widget

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Draws a draggable vertical scrollbar over the content of [state], aligned by [modifier] inside the surrounding box.
 * Drawn on desktop only; Android, iOS and web draw nothing.
 */
@Composable
expect fun BoxScope.HomebaseVerticalScrollbar(modifier: Modifier, state: LazyGridState)

/** [HomebaseVerticalScrollbar] for a lazy list: drawn on desktop only. */
@Composable
expect fun BoxScope.HomebaseVerticalScrollbar(modifier: Modifier, state: LazyListState)

/** [HomebaseVerticalScrollbar] for a scrolling column: drawn on desktop only. */
@Composable
expect fun BoxScope.HomebaseVerticalScrollbar(modifier: Modifier, state: ScrollState)