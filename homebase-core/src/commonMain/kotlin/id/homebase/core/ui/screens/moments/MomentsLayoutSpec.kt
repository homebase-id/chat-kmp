package id.homebase.core.ui.screens.moments

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.core.moments.MomentsAlbumZoom
import kotlin.uuid.Uuid

internal object MomentsLayoutSpec {
    val FeedMaxWidth = 600.dp
    val SelectionRingWidth = 3.dp
    val SelectionInset = 8.dp
    val AlbumCellSpacing = 4.dp
    val AlbumHorizontalPadding = 16.dp
    val FeedBottomPadding = 112.dp
    val DetailPaneMargin = 24.dp
    // The feed cards' own 16dp inset makes up the rest of the 24dp pane spacing.
    val DetailPaneGutter = 8.dp
    val DetailStageInset = 12.dp
    val DockedCommentsRailWidth = CommentsRailWidth
    val MinDetailMediaWidth = 300.dp
    const val FeedPaneWidthFraction = 0.28f
    val FeedPaneMinWidth = 380.dp
    val FeedPaneMaxWidth = 480.dp
    val DetailPaneMinWidth = FeedPaneMinWidth + DockedCommentsRailWidth + MinDetailMediaWidth

    fun showsDetailPane(availableWidth: Dp): Boolean = availableWidth >= DetailPaneMinWidth

    fun feedColumnInset(feedWidth: Dp): Dp = ((feedWidth - FeedMaxWidth) / 2).coerceAtLeast(0.dp)

    fun dockedRailWidth(detailWidth: Dp): Dp =
        minOf(DockedCommentsRailWidth, detailWidth - DetailStageInset - MinDetailMediaWidth)

    fun feedPaneWidth(availableWidth: Dp): Dp =
        (availableWidth * FeedPaneWidthFraction).coerceIn(FeedPaneMinWidth, FeedPaneMaxWidth)

    fun resolveSelection(current: Uuid?, newestFirstIds: List<Uuid>): Uuid? =
        if (current != null && current in newestFirstIds) current else newestFirstIds.firstOrNull()

    fun phoneAlbumColumns(zoom: MomentsAlbumZoom): Int = when (zoom) {
        MomentsAlbumZoom.Day -> 3
        MomentsAlbumZoom.Month -> 5
        MomentsAlbumZoom.Year -> 8
    }

    fun albumColumnCount(zoom: MomentsAlbumZoom, gridWidth: Dp): Int {
        val phoneColumns = phoneAlbumColumns(zoom)
        val fitted = (gridWidth + AlbumCellSpacing) / (albumMinCellWidth(zoom) + AlbumCellSpacing)
        return maxOf(phoneColumns, fitted.toInt())
    }

    // LazyGrid hands out the remainder pixel by pixel, which makes square cells in one row differ in height.
    fun albumCellSizePx(availablePx: Int, spacingPx: Int, columns: Int): Int =
        (availablePx - spacingPx * (columns - 1)) / columns

    private fun albumMinCellWidth(zoom: MomentsAlbumZoom): Dp = when (zoom) {
        MomentsAlbumZoom.Day -> 120.dp
        MomentsAlbumZoom.Month -> 76.dp
        MomentsAlbumZoom.Year -> 48.dp
    }
}
