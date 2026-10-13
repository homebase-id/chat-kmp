package id.homebase.app

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPreferencesMiniModeTest {
    private val screen = listOf(DpRect(0.dp, 0.dp, 1440.dp, 900.dp))

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
    fun aSavedSpotOffEveryScreenFallsBackToTheCornerAndOneAtTheEdgeIsPulledBackIn() {
        val size = miniCallSize(false)
        val corner = miniCallPosition(null, size, screen)
        assertEquals(corner, miniCallPosition(WindowPosition.Absolute(3000.dp, 40.dp), size, screen))
        assertEquals(WindowPosition.Absolute(1440.dp - 280.dp, 812.dp), miniCallPosition(WindowPosition.Absolute(1250.dp, 850.dp), size, screen))
    }

    @Test
    fun aSavedSpotOnASecondMonitorIsKeptWhileItIsConnected() {
        val two = screen + DpRect(1440.dp, 0.dp, 3360.dp, 1080.dp)
        val saved = WindowPosition.Absolute(2000.dp, 300.dp)
        assertEquals(saved, miniCallPosition(saved, miniCallSize(true), two))
        assertEquals(miniCallPosition(null, miniCallSize(true), screen), miniCallPosition(saved, miniCallSize(true), screen))
    }

    @Test
    fun miniPositionIsRememberedAcrossRestartsAndSharedByVideoAndVoice() {
        val settings = InMemorySettings()
        assertNull(DesktopPreferences(settings).miniWindowPosition())
        DesktopPreferences(settings).saveMiniWindowPosition(WindowPosition.Absolute(1000.dp, 700.dp))
        assertEquals(WindowPosition.Absolute(1000.dp, 700.dp), DesktopPreferences(settings).miniWindowPosition())
    }

    @Test
    fun leavingMiniModeBringsBackTheSameFullWindowEvenWhenItWasMaximised() {
        val full = WindowState(placement = WindowPlacement.Maximized, position = WindowPosition.Absolute(40.dp, 60.dp), size = DpSize(1100.dp, 700.dp))
        val modes = CallWindowModes(full)
        modes.enterMini(focusable = false)
        assertEquals(MiniEntry(focusable = false), modes.mini)
        modes.exitMini()
        assertNull(modes.mini)
        assertEquals(WindowPlacement.Maximized, modes.full.placement)
        assertEquals(DpSize(1100.dp, 700.dp), modes.full.size)
        assertEquals(WindowPosition.Absolute(40.dp, 60.dp), modes.full.position)
    }

    @Test
    fun theMiniSpotAndTheFullGeometryAreSeparateKeys() {
        val prefs = DesktopPreferences(InMemorySettings())
        val full = CallWindowGeometry(1100.dp, 700.dp, WindowPosition.Absolute(40.dp, 60.dp))
        prefs.saveCallWindowGeometry(video = true, full)
        prefs.saveMiniWindowPosition(WindowPosition.Absolute(10.dp, 20.dp))
        assertEquals(full, prefs.callWindowGeometry(video = true))
        assertEquals(WindowPosition.Absolute(10.dp, 20.dp), prefs.miniWindowPosition())
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
