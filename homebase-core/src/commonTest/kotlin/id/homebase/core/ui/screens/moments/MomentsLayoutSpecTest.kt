package id.homebase.core.ui.screens.moments

import androidx.compose.ui.unit.dp
import id.homebase.core.moments.MomentsAlbumZoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MomentsLayoutSpecTest {

    private val railAndDivider = 65.dp
    private val windows = listOf(900.dp, 1200.dp, 1440.dp, 1920.dp)

    @Test
    fun defaultDesktopWindowGetsTheDetailPane() {
        assertTrue(MomentsLayoutSpec.showsDetailPane(1200.dp - railAndDivider))
    }

    @Test
    fun narrowWindowsKeepTheSheet() {
        assertFalse(MomentsLayoutSpec.showsDetailPane(900.dp - railAndDivider))
        assertFalse(MomentsLayoutSpec.showsDetailPane(1079.dp))
        assertTrue(MomentsLayoutSpec.showsDetailPane(1080.dp))
    }

    @Test
    fun phonesAreUnchanged() {
        assertFalse(MomentsLayoutSpec.showsDetailPane(411.dp))
    }

    @Test
    fun feedPaneIsClampedAndLeavesRoomForTheDetail() {
        for (w in windows) {
            val available = w - railAndDivider
            if (!MomentsLayoutSpec.showsDetailPane(available)) continue
            val pane = MomentsLayoutSpec.feedPaneWidth(available)
            assertTrue(pane >= 380.dp && pane <= 480.dp, "pane $pane at $w")
            val media = available - pane - MomentsLayoutSpec.DockedCommentsRailWidth
            assertTrue(media >= MomentsLayoutSpec.MinDetailMediaWidth, "media $media at $w")
        }
    }

    @Test
    fun thresholdIsFeedPlusRailPlusMinimumMedia() {
        assertEquals(
            MomentsLayoutSpec.DetailPaneMinWidth,
            380.dp + MomentsLayoutSpec.DockedCommentsRailWidth + MomentsLayoutSpec.MinDetailMediaWidth,
        )
    }

    @Test
    fun selectionDefaultsToNewest() {
        val ids = listOf(Uuid.random(), Uuid.random())
        assertEquals(ids[0], MomentsLayoutSpec.resolveSelection(null, ids))
    }

    @Test
    fun selectionSurvivesWhileItStillExists() {
        val ids = listOf(Uuid.random(), Uuid.random(), Uuid.random())
        assertEquals(ids[1], MomentsLayoutSpec.resolveSelection(ids[1], ids))
    }

    @Test
    fun deletingTheSelectedMomentFallsBackToTheNewestSurvivor() {
        val ids = listOf(Uuid.random(), Uuid.random(), Uuid.random())
        val survivors = ids.filter { it != ids[1] }
        assertEquals(survivors[0], MomentsLayoutSpec.resolveSelection(ids[1], survivors))
    }

    @Test
    fun emptyFeedHasNoSelection() {
        assertNull(MomentsLayoutSpec.resolveSelection(Uuid.random(), emptyList()))
    }

    @Test
    fun albumKeepsPhoneColumnsOnEveryPhoneWidth() {
        for (phoneWidth in 320..430) {
            val grid = phoneWidth.dp - MomentsLayoutSpec.AlbumHorizontalPadding * 2
            assertEquals(3, MomentsLayoutSpec.albumColumnCount(MomentsAlbumZoom.Day, grid))
            assertEquals(5, MomentsLayoutSpec.albumColumnCount(MomentsAlbumZoom.Month, grid))
            assertEquals(8, MomentsLayoutSpec.albumColumnCount(MomentsAlbumZoom.Year, grid))
        }
    }

    @Test
    fun albumAddsColumnsInsteadOfGrowingCellsAtTheFeedCap() {
        val grid = MomentsLayoutSpec.FeedMaxWidth - MomentsLayoutSpec.AlbumHorizontalPadding * 2
        assertEquals(4, MomentsLayoutSpec.albumColumnCount(MomentsAlbumZoom.Day, grid))
        assertEquals(7, MomentsLayoutSpec.albumColumnCount(MomentsAlbumZoom.Month, grid))
        assertEquals(11, MomentsLayoutSpec.albumColumnCount(MomentsAlbumZoom.Year, grid))
    }

    @Test
    fun albumCellsInARowShareOneWholePixelSize() {
        assertEquals(263, MomentsLayoutSpec.albumCellSizePx(availablePx = 800, spacingPx = 5, columns = 3))
        assertEquals(281, MomentsLayoutSpec.albumCellSizePx(availablePx = 1138, spacingPx = 4, columns = 4))
    }

    @Test
    fun feedChromeLinesUpWithTheCappedColumn() {
        assertEquals(0.dp, MomentsLayoutSpec.feedColumnInset(411.dp))
        assertEquals(150.dp, MomentsLayoutSpec.feedColumnInset(900.dp))
    }

    @Test
    fun railFollowsItsPaneBetweenItsBounds() {
        assertEquals(400.dp, MomentsLayoutSpec.dockedRailWidth(1400.dp))
        assertEquals(240.dp, MomentsLayoutSpec.dockedRailWidth(300.dp))
        val narrower = MomentsLayoutSpec.dockedRailWidth(600.dp)
        val wider = MomentsLayoutSpec.dockedRailWidth(800.dp)
        assertTrue(narrower < wider && wider < 400.dp, "$narrower vs $wider")
    }

    @Test
    fun theNarrowestDetailPaneStillFitsRailAndMedia() {
        val detail = MomentsLayoutSpec.DetailPaneDragMinWidth -
            MomentsLayoutSpec.DetailPaneGutter - MomentsLayoutSpec.DetailPaneMargin
        val media = detail - MomentsLayoutSpec.DetailStageInset - MomentsLayoutSpec.dockedRailWidth(detail)
        assertTrue(media >= 160.dp, "media $media")
    }

    @Test
    fun draggedFeedPaneStopsAtBothMinimums() {
        val available = 1440.dp - railAndDivider
        assertEquals(280.dp, MomentsLayoutSpec.feedPaneWidth(available, 100.dp))
        assertEquals(available - 460.dp, MomentsLayoutSpec.feedPaneWidth(available, available))
        assertEquals(800.dp, MomentsLayoutSpec.feedPaneWidth(available, 800.dp))
    }

    @Test
    fun dragDeltasAccumulateWithinTheRange() {
        val available = 1440.dp - railAndDivider
        var width = MomentsLayoutSpec.feedPaneWidth(available)
        val start = width
        width = MomentsLayoutSpec.feedPaneWidth(available, width + 120.dp)
        assertEquals(start + 120.dp, width)
        repeat(50) { width = MomentsLayoutSpec.feedPaneWidth(available, width + 40.dp) }
        assertEquals(MomentsLayoutSpec.feedPaneWidthRange(available).endInclusive, width)
        // Overshooting the end does not bank distance: the first drag back moves the pane.
        width = MomentsLayoutSpec.feedPaneWidth(available, width - 10.dp)
        assertEquals(MomentsLayoutSpec.feedPaneWidthRange(available).endInclusive - 10.dp, width)
        repeat(50) { width = MomentsLayoutSpec.feedPaneWidth(available, width - 40.dp) }
        assertEquals(280.dp, width)
    }

    @Test
    fun aStoredWidthIsReclampedForASmallerWindow() {
        val stored = 900.dp
        val atThreshold = MomentsLayoutSpec.DetailPaneMinWidth
        assertEquals(atThreshold - 460.dp, MomentsLayoutSpec.feedPaneWidth(atThreshold, stored))
        assertEquals(280.dp..(atThreshold - 460.dp), MomentsLayoutSpec.feedPaneWidthRange(atThreshold))
    }

    @Test
    fun withoutAStoredWidthThePaneFollowsTheWindow() {
        assertEquals(MomentsLayoutSpec.feedPaneWidth(1855.dp), MomentsLayoutSpec.feedPaneWidth(1855.dp, null))
        assertEquals(480.dp, MomentsLayoutSpec.feedPaneWidth(1855.dp))
    }
}
