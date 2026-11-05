package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import id.homebase.photobase.ui.PhotobaseSettingsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PhotobaseSettingsScreenTest {
    private fun ComposeUiTest.page(
        onAction: (SettingsUiAction) -> Unit = {},
        backupSection: (@Composable () -> Unit)? = null,
    ) = setContent {
        MaterialTheme {
            PhotobaseSettingsUi(SettingsUiState(), onAction, PhotobaseSettingsUiState(), {}, {}, backupSection)
        }
    }

    @Test
    fun theIconToggleFiresItsAction() = runComposeUiTest {
        val seen = mutableListOf<SettingsUiAction>()
        page(onAction = { seen += it })
        onNodeWithTag("photobaseIconToggle").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf<SettingsUiAction>(SettingsUiAction.SetPhotobaseIconVisible(true)), seen)
    }

    @Test
    fun theIntervalRowIsThereWithoutBackup() = runComposeUiTest {
        page()
        onNodeWithTag("photobasePushIntervalRow").assertExists()
    }

    @Test
    fun backupSitsUnderTheIntervalRowUnderTheIconToggle() = runComposeUiTest {
        page(backupSection = { Box(Modifier.testTag(BACKUP_TAG).size(10.dp)) })
        val icon = onNodeWithTag("photobaseIconToggle").getUnclippedBoundsInRoot().top
        val interval = onNodeWithTag("photobasePushIntervalRow").getUnclippedBoundsInRoot().top
        val backup = onNodeWithTag(BACKUP_TAG).getUnclippedBoundsInRoot().top
        assertTrue(icon < interval && interval < backup, "icon=$icon interval=$interval backup=$backup")
    }

    private companion object {
        const val BACKUP_TAG = "backupSection"
    }
}
