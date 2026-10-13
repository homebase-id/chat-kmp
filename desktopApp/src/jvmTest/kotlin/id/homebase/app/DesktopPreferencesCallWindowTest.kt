package id.homebase.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPreferencesCallWindowTest {
    @Test
    fun defaultsDifferForVideoAndVoice() {
        val prefs = DesktopPreferences(InMemorySettings())
        assertEquals(CallWindowGeometry(960.dp, 640.dp, WindowPosition.PlatformDefault), prefs.callWindowGeometry(video = true))
        assertEquals(CallWindowGeometry(420.dp, 560.dp, WindowPosition.PlatformDefault), prefs.callWindowGeometry(video = false))
    }

    @Test
    fun geometryRoundTripsSeparatelyForVideoAndVoice() {
        val settings = InMemorySettings()
        val video = CallWindowGeometry(1100.dp, 700.dp, WindowPosition.Absolute(40.dp, 60.dp))
        val voice = CallWindowGeometry(400.dp, 500.dp, WindowPosition.Absolute(900.dp, 20.dp))
        DesktopPreferences(settings).apply {
            saveCallWindowGeometry(video = true, video)
            saveCallWindowGeometry(video = false, voice)
        }
        val reread = DesktopPreferences(settings)
        assertEquals(video, reread.callWindowGeometry(video = true))
        assertEquals(voice, reread.callWindowGeometry(video = false))
    }

    @Test
    fun savedSizeBelowTheMinimumIsRaisedToIt() {
        val settings = InMemorySettings()
        DesktopPreferences(settings).saveCallWindowGeometry(false, CallWindowGeometry(100.dp, 50.dp, WindowPosition.PlatformDefault))
        assertEquals(CallWindowGeometry(320.dp, 240.dp, WindowPosition.PlatformDefault), DesktopPreferences(settings).callWindowGeometry(false))
    }

    @Test
    fun callWindowKeysDoNotTouchTheMainWindow() {
        val settings = InMemorySettings()
        val prefs = DesktopPreferences(settings)
        prefs.saveCallWindowGeometry(true, CallWindowGeometry(1100.dp, 700.dp, WindowPosition.Absolute(1.dp, 2.dp)))
        assertEquals(1200.dp, prefs.windowWidthDp)
        assertEquals(WindowPosition.PlatformDefault, prefs.windowPosition)
    }
}
