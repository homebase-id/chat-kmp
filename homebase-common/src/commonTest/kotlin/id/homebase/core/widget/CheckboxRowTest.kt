package id.homebase.core.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CheckboxRowTest {

    @Test
    fun displaysLabel() = runComposeUiTest {
        setContent {
            MaterialTheme {
                CheckboxRow(
                    label = "Enable notifications",
                    checked = false,
                    onCheckedChange = {},
                )
            }
        }
        onNodeWithText("Enable notifications").assertExists()
    }

    @Test
    fun clickingRowTogglesValue() = runComposeUiTest {
        var lastValue: Boolean? = null
        setContent {
            MaterialTheme {
                CheckboxRow(
                    label = "Enable notifications",
                    checked = false,
                    onCheckedChange = { lastValue = it },
                )
            }
        }
        onNodeWithText("Enable notifications").performClick()
        assertEquals(true, lastValue)
    }

    @Test
    fun clickingCheckedRowPassesFalse() = runComposeUiTest {
        var lastValue: Boolean? = null
        setContent {
            MaterialTheme {
                CheckboxRow(
                    label = "Enable notifications",
                    checked = true,
                    onCheckedChange = { lastValue = it },
                )
            }
        }
        onNodeWithText("Enable notifications").performClick()
        assertEquals(false, lastValue)
    }
}
