package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MessageLongPressLayoutTest {

    private var visible by mutableStateOf(true)

    @Test
    fun `the lifted bubble is gone by the time the scrim is`() = runSkikoComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.size(200.dp).background(Color.White).testTag(ROOT)) {
                    // As in PopupWithScrim: no parent fade, so each child's own exit is what's measured.
                    AnimatedVisibility(visible, enter = EnterTransition.None, exit = ExitTransition.None) {
                        MessageLongPressLayout(
                            alignEnd = false,
                            reactionMenu = null,
                            bubble = { Box(Modifier.size(40.dp).background(Color.Red)) },
                            actionMenu = { Box(Modifier.size(40.dp).testTag(MENU)) },
                        )
                    }
                }
            }
        }
        waitForIdle()
        assertTrue(redDrawn(), "the bubble copy should be drawn while the overlay is up")

        mainClock.autoAdvance = false
        visible = false
        mainClock.advanceTimeBy(192)

        onNodeWithTag(MENU).assertExists("the menu's 220ms shrink should still be running")
        assertTrue(!redDrawn(), "the bubble copy is still drawn after the 150ms hide")
    }

    private fun SkikoComposeUiTest.redDrawn(): Boolean {
        val pixels = onNodeWithTag(ROOT).captureToImage().toPixelMap()
        return (0 until pixels.width).any { x -> (0 until pixels.height).any { y -> pixels[x, y].green < 0.9f } }
    }

    private companion object {
        const val ROOT = "root"
        const val MENU = "menu"
    }
}
