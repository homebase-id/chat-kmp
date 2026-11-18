package id.homebase.core.ui.screens.card

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CardPointerTest {

    private fun wheel(wheelScrolls: Boolean): Int {
        var value = -1
        runDesktopComposeUiTest(400, 200) {
            lateinit var scroll: ScrollState
            setContent {
                scroll = rememberScrollState()
                Row(
                    Modifier.width(200.dp).testTag("row")
                        .then(if (wheelScrolls) Modifier.wheelScrollsRow(scroll) else Modifier)
                        .horizontalScroll(scroll),
                ) { repeat(20) { Box(Modifier.size(60.dp)) } }
            }
            onNodeWithTag("row").performMouseInput { scroll(3f, ScrollWheel.Vertical) }
            waitForIdle()
            value = scroll.value
        }
        return value
    }

    @Test
    fun aPlainMouseWheelScrollsAHorizontalRow() {
        assertEquals(0, wheel(wheelScrolls = false), "Compose itself ignores a vertical wheel on a horizontal row")
        assertTrue(wheel(wheelScrolls = true) > 0)
    }
}
