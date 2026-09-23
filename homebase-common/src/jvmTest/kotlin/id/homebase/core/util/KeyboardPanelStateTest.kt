package id.homebase.core.util

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class KeyboardPanelStateTest {

    private val keyboardPx = mutableIntStateOf(0)

    private val fakeIme = ImeOffsetState(
        imeInsets = object : WindowInsets {
            override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
            override fun getTop(density: Density) = 0
            override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
            override fun getBottom(density: Density) = keyboardPx.intValue
        },
        navBarInsets = WindowInsets(0, 0, 0, 0),
        density = Density(1f),
    )

    private val slide = listOf(40, 300, 650, 800)

    @Test
    fun keyboardAndPanelSwapAtTheSameHeight() = runComposeUiTest {
        mainClock.autoAdvance = false
        lateinit var panel: KeyboardPanelState
        setContent {
            MaterialTheme { panel = rememberKeyboardPanelState(fakeIme, hasSoftKeyboard = true) }
        }
        fun frame(px: Int) {
            keyboardPx.intValue = px
            mainClock.advanceTimeByFrame()
            waitForIdle()
        }

        slide.forEach(::frame)
        frame(800)
        assertEquals(800, panel.heightPx)

        runOnIdle { panel.open() }
        frame(800)
        slide.reversed().plus(0).forEach {
            frame(it)
            assertEquals(800, panel.contentInsetPx, "keyboard -> panel at ime=$it")
        }

        runOnIdle { panel.closeForKeyboard() }
        listOf(0).plus(slide).forEach {
            frame(it)
            assertEquals(800, panel.contentInsetPx, "panel -> keyboard at ime=$it")
        }
        frame(800)
        frame(800)
        assertEquals(800, panel.contentInsetPx)
        assertFalse(panel.isPanelComposed)
    }

    @Test
    fun firstFrameOfTheKeyboardIsNotItsHeight() = runComposeUiTest {
        mainClock.autoAdvance = false
        lateinit var panel: KeyboardPanelState
        setContent {
            MaterialTheme { panel = rememberKeyboardPanelState(fakeIme, hasSoftKeyboard = true) }
        }
        slide.plus(800).plus(slide.reversed()).plus(0).forEach {
            keyboardPx.intValue = it
            mainClock.advanceTimeByFrame()
            waitForIdle()
        }
        assertEquals(800, panel.heightPx)
    }

    @Test
    fun panelOpenedWithoutKeyboardSlidesUp() = runComposeUiTest {
        mainClock.autoAdvance = false
        lateinit var panel: KeyboardPanelState
        setContent {
            MaterialTheme { panel = rememberKeyboardPanelState(fakeIme, hasSoftKeyboard = true) }
        }
        runOnIdle { panel.open() }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        waitForIdle()
        val midway = panel.contentInsetPx
        assertTrue(midway in 1 until panel.heightPx, "reveal mid-slide was $midway")
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        assertEquals(panel.heightPx, panel.contentInsetPx)
    }
}
