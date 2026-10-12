package id.homebase.chat.calling

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallLayoutMathTest {
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
