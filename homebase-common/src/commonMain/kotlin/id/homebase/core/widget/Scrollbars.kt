package id.homebase.core.widget

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun BoxScope.HomebaseVerticalScrollbar(modifier: Modifier, state: LazyGridState)

@Composable
expect fun BoxScope.HomebaseVerticalScrollbar(modifier: Modifier, state: LazyListState)

@Composable
expect fun BoxScope.HomebaseVerticalScrollbar(modifier: Modifier, state: ScrollState)