package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class EmptyListItemTest {

    @Test
    fun displaysGivenText() = runComposeUiTest {
        setContent {
            MaterialTheme {
                EmptyListItem(text = "No conversations yet")
            }
        }
        onNodeWithText("No conversations yet").assertExists()
    }
}
