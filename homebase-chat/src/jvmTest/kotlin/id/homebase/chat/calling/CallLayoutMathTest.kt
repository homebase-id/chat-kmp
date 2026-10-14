package id.homebase.chat.calling

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallLayoutMathTest {
    @Test
    fun floatingTileRestsInsideTheInsetsAndClearances() {
        val corners = snapCorners(Rect(left = 36f, top = 300f, right = 1080f - 36f, bottom = 2400f - 400f), Size(336f, 504f))
        assertEquals(listOf(Offset(36f, 300f), Offset(708f, 300f), Offset(36f, 1496f), Offset(708f, 1496f)), corners)
    }

    @Test
    fun aKeyboardTallerThanTheRoomPinsTheTileToTheTop() {
        val corners = snapCorners(Rect(12f, 100f, 388f, 200f), Size(112f, 168f))
        assertEquals(100f, corners[2].y)
        assertEquals(100f, corners[3].y)
        val narrow = snapCorners(Rect(12f, 0f, 60f, 400f), Size(112f, 168f))
        assertEquals(12f, narrow[1].x)
    }

    @Test
    fun aReleasedTileRestsInTheCornerNearestWhereItWouldCoast() {
        val corners = listOf(Offset(0f, 0f), Offset(300f, 0f), Offset(0f, 600f), Offset(300f, 600f))
        assertEquals(0, restingCorner(corners, Offset(100f, 200f), Offset.Zero))
        assertEquals(3, restingCorner(corners, Offset(200f, 400f), Offset.Zero))
        assertEquals(1, restingCorner(corners, Offset(100f, 200f), Offset(2_000f, -1_000f)), "a fling carries it across")
        assertEquals(2, restingCorner(corners, Offset(160f, 280f), Offset(-400f, 2_000f)))
    }

    @Test
    fun theTileTurnsWithTheRemoteVideo() {
        assertEquals(DpSize(112.dp, 168.dp), floatingTileSize(null))
        assertEquals(DpSize(112.dp, 168.dp), floatingTileSize(720 to 1280))
        assertEquals(DpSize(168.dp, 112.dp), floatingTileSize(1280 to 720))
    }

    @Test
    fun phonesOnTheirSideAreCompactWhateverTheirWidth() {
        assertTrue(isCompactHeight(852.dp, 393.dp))
        assertTrue(isCompactHeight(915.dp, 411.dp))
        assertTrue(isCompactHeight(640.dp, 360.dp))
        assertFalse(isCompactHeight(393.dp, 852.dp))
        assertFalse(isCompactHeight(1280.dp, 800.dp))
        assertFalse(isCompactHeight(1194.dp, 834.dp))
    }

    @Test
    fun bottomCornersNeverRiseAboveTheTopOnes() {
        val tall = previewCorners(width = 390f, height = 844f, pipW = 108f, pipH = 156f, margin = 16f, top = 84f, bottomReserve = 236f)
        assertEquals(listOf(Offset(16f, 84f), Offset(266f, 84f), Offset(16f, 452f), Offset(266f, 452f)), tall)
        val short = previewCorners(width = 844f, height = 326f, pipW = 192f, pipH = 132f, margin = 16f, top = 80f, bottomReserve = 128f)
        assertEquals(80f, short[2].y)
        assertEquals(80f, short[3].y)
    }

    @Test
    fun remoteVideoFillsWhenTurnedLikeTheScreenAndFitsWhenTurnedAway() {
        assertNull(remoteVideoLetterbox(null, 390f, 844f))
        assertNull(remoteVideoLetterbox(0 to 0, 390f, 844f))
        assertNull(remoteVideoLetterbox(720 to 1280, 390f, 844f))
        assertNull(remoteVideoLetterbox(1280 to 720, 844f, 390f))
        assertEquals(1280f / 720, remoteVideoLetterbox(1280 to 720, 390f, 844f))
        assertEquals(720f / 1280, remoteVideoLetterbox(720 to 1280, 844f, 390f))
    }
}
