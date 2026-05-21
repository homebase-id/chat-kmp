package id.homebase.core.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

data class MediaRailItem(
    val key: String,
)

@Composable
fun MediaRail(
    items: List<MediaRailItem>,
    selectedIndex: Int,
    onItemSelected: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    itemSize: Dp = 60.dp,
    itemSpacing: Dp = 8.dp,
    itemContent: @Composable (item: MediaRailItem, index: Int, isSelected: Boolean) -> Unit,
) {
    if (items.size <= 1) return

    val listState = rememberLazyListState()
    val density = LocalDensity.current

    LaunchedEffect(selectedIndex) {
        if (selectedIndex !in items.indices) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.viewportSize.width }.first { it > 0 }
        val itemSizePx = with(density) { itemSize.toPx() }
        val viewportWidth = listState.layoutInfo.viewportSize.width.toFloat()
        val scrollOffset = -((viewportWidth - itemSizePx) / 2f).toInt()
        listState.animateScrollToItem(selectedIndex, scrollOffset)
    }

    LazyRow(
        state = listState,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        contentPadding = PaddingValues(horizontal = itemSpacing),
    ) {
        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            Box(
                modifier = Modifier
                    .size(itemSize)
                    .clickable(role = Role.Tab) { onItemSelected(index) },
            ) {
                itemContent(item, index, index == selectedIndex)
            }
        }
    }
}
