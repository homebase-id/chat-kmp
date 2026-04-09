package id.homebase.chat.widget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RichTextStyleButtonTest {

    @Test
    fun clickCallbackFires() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                RichTextStyleButton(
                    onClick = { clicked = true },
                    icon = Icons.Filled.FormatBold,
                )
            }
        }
        onNodeWithContentDescription("Filled.FormatBold").performClick()
        assertTrue(clicked)
    }

    @Test
    fun disabledButtonDoesNotFireCallback() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                RichTextStyleButton(
                    onClick = { clicked = true },
                    icon = Icons.Filled.FormatBold,
                    enabled = false,
                )
            }
        }
        onNodeWithContentDescription("Filled.FormatBold").performClick()
        assertTrue(!clicked)
    }
}
