package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MessageLongPressLayoutTest {

    private var visible by mutableStateOf(true)
    private lateinit var handback: BubbleHandback

    // Row at (0,0)-(40,40); the overlay is offset (30,10), so its lifted bubble rests at (30,50)-(70,90).
    private fun SkikoComposeUiTest.setUpRowAndOverlay() {
        setContent {
            MaterialTheme {
                handback = rememberBubbleHandback()
                handback.active = visible
                Box(Modifier.size(200.dp).background(Color.White).testTag(ROOT)) {
                    Box(Modifier.size(40.dp).handbackSource(handback).background(Color.Red))
                    // As in PopupWithScrim: no parent fade, so each child's own exit is what's measured.
                    AnimatedVisibility(visible, enter = EnterTransition.None, exit = ExitTransition.None) {
                        Box(Modifier.offset(30.dp, 10.dp)) {
                            MessageLongPressLayout(
                                alignEnd = false,
                                reactionMenu = null,
                                bubble = { HandbackBubble(handback) },
                                actionMenu = { Box(Modifier.size(40.dp).testTag(MENU)) },
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `the real row is hidden and the snapshot sits at the lifted spot while the overlay is up`() =
        runSkikoComposeUiTest {
            setUpRowAndOverlay()

            assertTrue(handback.hidden)
            assertFalse(redAt(10, 10), "the real row is still drawn under the overlay")
            assertTrue(redAt(50, 70), "the recorded bubble should be drawn at the lifted spot")
        }

    @Test
    fun `the snapshot lands on the row while the menu is still shrinking, then the row is restored`() =
        runSkikoComposeUiTest {
            setUpRowAndOverlay()

            mainClock.autoAdvance = false
            visible = false
            mainClock.advanceTimeBy(160)

            onNodeWithTag(MENU).assertExists("the menu's 220ms shrink should still be running")
            assertTrue(handback.hidden, "the row must stay hidden until the snapshot is home")
            assertTrue(redAt(10, 10), "the snapshot should be back over the row")
            assertFalse(redAt(50, 70), "only one bubble may be visible")

            mainClock.autoAdvance = true
            waitForIdle()

            assertFalse(handback.hidden, "the row was never restored after the overlay closed")
            assertTrue(redAt(10, 10), "the restored row should draw its own content")
            assertFalse(redAt(50, 70))
        }

    private fun SkikoComposeUiTest.redAt(xDp: Int, yDp: Int): Boolean {
        val pixels = onNodeWithTag(ROOT).captureToImage().toPixelMap()
        val scale = pixels.width / 200f
        val p = pixels[(xDp * scale).toInt(), (yDp * scale).toInt()]
        return p.red > 0.9f && p.green < 0.2f
    }

    private companion object {
        const val ROOT = "root"
        const val MENU = "menu"
    }
}
