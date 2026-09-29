package id.homebase.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class ThemeSwapCrossfadeTest {

    @Test
    fun themeSwapBlendsThroughOldFrameThenSettlesOnNewTheme() = runDesktopComposeUiTest {
        var dark by mutableStateOf(false)
        setContent {
            HomebaseTheme(darkTheme = dark, updatesSystemChrome = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            }
        }
        fun pixel() = onRoot().captureToImage().toPixelMap()[2, 2]

        val light = pixel()
        mainClock.autoAdvance = false
        dark = true
        repeat(6) { mainClock.advanceTimeByFrame() }
        val mid = pixel()
        mainClock.autoAdvance = true
        waitForIdle()
        val settled = pixel()

        assertNotEquals(light, settled)
        assertNotEquals(mid, settled)
        assertNotEquals(mid, light)
        assertTrue(settled.red < 0.5f)
    }
}
