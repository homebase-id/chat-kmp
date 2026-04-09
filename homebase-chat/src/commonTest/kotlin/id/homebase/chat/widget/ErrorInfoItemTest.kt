package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ErrorInfoItemTest {

    @Test
    fun displaysErrorText() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ErrorInfoItem(text = "Something went wrong")
            }
        }
        onNodeWithText("Something went wrong").assertExists()
    }
}
