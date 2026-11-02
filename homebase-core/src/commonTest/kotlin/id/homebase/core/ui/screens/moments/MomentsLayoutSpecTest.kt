package id.homebase.core.ui.screens.moments

import androidx.compose.ui.unit.dp
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
        assertEquals(411.dp, MomentsLayoutSpec.feedColumnWidth(411.dp))
        assertFalse(MomentsLayoutSpec.showsDetailPane(411.dp))
    }

    @Test
    fun feedColumnNeverExceedsTheCapAtAnyWindow() {
        for (w in windows) {
            val available = w - railAndDivider
            val column = MomentsLayoutSpec.feedColumnWidth(available)
            assertTrue(column <= MomentsLayoutSpec.FeedMaxWidth, "column $column at $w")
            assertTrue(column <= available)
        }
        assertEquals(600.dp, MomentsLayoutSpec.feedColumnWidth(835.dp))
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
    fun albumCellsStayPhoneSizedOnWideScreens() {
        for (zoom in id.homebase.core.moments.MomentsAlbumZoom.entries) {
            val cell = MomentsLayoutSpec.albumMinCellWidth(zoom)
            val phoneColumns = (411 / cell.value).toInt()
            assertEquals(
                when (zoom) {
                    id.homebase.core.moments.MomentsAlbumZoom.Day -> 3
                    id.homebase.core.moments.MomentsAlbumZoom.Month -> 5
                    id.homebase.core.moments.MomentsAlbumZoom.Year -> 8
                },
                phoneColumns,
            )
        }
    }
}
