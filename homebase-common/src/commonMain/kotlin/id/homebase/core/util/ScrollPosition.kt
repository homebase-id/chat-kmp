package id.homebase.core.util

import androidx.compose.runtime.Immutable

@Immutable
data class ScrollPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
    val triggerScroll: Boolean = false,
)