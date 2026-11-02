package id.homebase.core.ui.screens.moments

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.core.moments.MomentsAlbumZoom
import kotlin.uuid.Uuid

internal object MomentsLayoutSpec {
    val FeedMaxWidth = 600.dp
    val DockedCommentsRailWidth = CommentsRailWidth
    val MinDetailMediaWidth = 300.dp
    const val FeedPaneWidthFraction = 0.28f
    val FeedPaneMinWidth = 380.dp
    val FeedPaneMaxWidth = 480.dp
    val DetailPaneMinWidth = FeedPaneMinWidth + DockedCommentsRailWidth + MinDetailMediaWidth

    fun showsDetailPane(availableWidth: Dp): Boolean = availableWidth >= DetailPaneMinWidth

    fun feedColumnWidth(availableWidth: Dp): Dp = minOf(availableWidth, FeedMaxWidth)

    fun feedPaneWidth(availableWidth: Dp): Dp =
        (availableWidth * FeedPaneWidthFraction).coerceIn(FeedPaneMinWidth, FeedPaneMaxWidth)

    fun resolveSelection(current: Uuid?, newestFirstIds: List<Uuid>): Uuid? =
        if (current != null && current in newestFirstIds) current else newestFirstIds.firstOrNull()

    fun albumMinCellWidth(zoom: MomentsAlbumZoom): Dp = when (zoom) {
        MomentsAlbumZoom.Day -> 120.dp
        MomentsAlbumZoom.Month -> 76.dp
        MomentsAlbumZoom.Year -> 48.dp
    }
}
