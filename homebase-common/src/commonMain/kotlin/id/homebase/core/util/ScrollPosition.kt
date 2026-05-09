package id.homebase.core.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable

@Immutable
data class ScrollPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
    val triggerScroll: Boolean = false,
)

/**
 * Returns `firstVisibleItemIndex` clamped to a real index in `[0, itemsSize - 1]`,
 * or `null` if the list is empty.
 *
 * Compose's idiom for "land at the bottom on first frame, no flash" is
 * `LazyListState(firstVisibleItemIndex = Int.MAX_VALUE)` — LazyColumn clamps that
 * sentinel during its first measure pass. Anything reading `firstVisibleItemIndex`
 * BEFORE that measure runs (a `snapshotFlow {}` body, a `derivedStateOf`, a
 * save-scroll effect on the same frame the state was created) gets the raw
 * sentinel back. Using that as an array index walks ~2.1B iterations and freezes
 * the UI dispatcher for seconds (build 1419 watchdog landed at
 * `ConversationContent.kt`'s floatingDateLabel snapshotFlow with
 * `idx=2147483647 items=0`).
 */
fun LazyListState.boundedFirstVisibleItemIndex(itemsSize: Int): Int? {
    if (itemsSize <= 0) return null
    return firstVisibleItemIndex.coerceIn(0, itemsSize - 1)
}