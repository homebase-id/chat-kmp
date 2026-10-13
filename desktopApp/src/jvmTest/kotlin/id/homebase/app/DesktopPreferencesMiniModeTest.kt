package id.homebase.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPreferencesMiniModeTest {
    private val screen = DpSize(1440.dp, 900.dp)

    @Test
    fun miniSizesAreFixedPerCallKind() {
        assertEquals(DpSize(320.dp, 180.dp), miniCallSize(video = true))
        assertEquals(DpSize(280.dp, 88.dp), miniCallSize(video = false))
    }

    @Test
    fun firstMiniWindowSitsInTheScreenCornerAndLaterOnesWhereItWasLeft() {
        val size = miniCallSize(true)
        val first = miniCallPosition(null, size, screen)
        assertEquals(WindowPosition.Absolute(1440.dp - 320.dp - 24.dp, 900.dp - 180.dp - 72.dp), first)
        val dragged = WindowPosition.Absolute(10.dp, 20.dp)
        assertEquals(dragged, miniCallPosition(dragged, size, screen))
    }

    @Test
    fun miniPositionIsRememberedAcrossRestartsAndSharedByVideoAndVoice() {
        val settings = InMemorySettings()
        assertNull(DesktopPreferences(settings).miniWindowPosition())
        DesktopPreferences(settings).saveMiniWindowPosition(WindowPosition.Absolute(1000.dp, 700.dp))
        assertEquals(WindowPosition.Absolute(1000.dp, 700.dp), DesktopPreferences(settings).miniWindowPosition())
    }

    @Test
    fun enteringAndLeavingMiniModeRestoresTheFullWindowGeometryAndKeepsTheMiniSpot() {
        val settings = InMemorySettings()
        val full = CallWindowGeometry(1100.dp, 700.dp, WindowPosition.Absolute(40.dp, 60.dp))
        val prefs = DesktopPreferences(settings)
        // Entering: the frame persists the full window, the mini window opens at its own anchor.
        prefs.saveCallWindowGeometry(video = true, full)
        val mini = miniCallPosition(prefs.miniWindowPosition(), miniCallSize(true), screen)
        // The user drags the mini window; leaving persists the spot, and the full window reads its geometry back.
        val moved = WindowPosition.Absolute(mini.x - 200.dp, mini.y - 100.dp)
        prefs.saveMiniWindowPosition(moved)
        assertEquals(full, prefs.callWindowGeometry(video = true))
        assertEquals(moved, prefs.miniWindowPosition())
        // A second mini session starts where the first one ended.
        assertEquals(moved, miniCallPosition(prefs.miniWindowPosition(), miniCallSize(true), screen))
        // And the main window's own keys were never touched.
        assertEquals(1200.dp, prefs.windowWidthDp)
    }

    @Test
    fun autoMiniOnBlurIsOffByDefaultAndPersists() {
        val settings = InMemorySettings()
        assertFalse(DesktopPreferences(settings).callAutoMini)
        DesktopPreferences(settings).callAutoMini = true
        assertTrue(DesktopPreferences(settings).callAutoMini)
    }
}
