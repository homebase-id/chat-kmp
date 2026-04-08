package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class LoadingListItemTest {

    @Test
    fun rendersWithoutCrashing() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LoadingListItem()
            }
        }
        // LoadingListItem renders a CircularProgressIndicator with no accessible text.
        // This test verifies the composable renders without error.
        waitForIdle()
    }
}
