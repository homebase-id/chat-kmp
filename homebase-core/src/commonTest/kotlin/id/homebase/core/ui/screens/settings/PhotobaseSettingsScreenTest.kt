package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
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
import id.homebase.photobase.ui.PushIntervalRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PhotobaseSettingsScreenTest {
    private fun ComposeUiTest.page(
        onAction: (SettingsUiAction) -> Unit = {},
        backupExists: Boolean = false,
    ) = setContent {
        MaterialTheme {
            PhotobaseSettingsUi(
                uiState = SettingsUiState(),
                onAction = onAction,
                onBack = {},
                intervalRow = if (backupExists) ({ PushIntervalRow(PhotobaseSettingsUiState(), {}) }) else null,
                backupSection = if (backupExists) ({ Box(Modifier.testTag(BACKUP_TAG).size(10.dp)) }) else null,
            )
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
    fun onlyTheIconToggleShowsWhereBackupDoesNotExist() = runComposeUiTest {
        page()
        onNodeWithTag("photobaseIconToggle").assertExists()
        onNodeWithTag("photobasePushIntervalRow").assertDoesNotExist()
        onNodeWithTag(BACKUP_TAG).assertDoesNotExist()
    }

    @Test
    fun backupSitsUnderTheIntervalRowUnderTheIconToggle() = runComposeUiTest {
        page(backupExists = true)
        val icon = onNodeWithTag("photobaseIconToggle").getUnclippedBoundsInRoot().top
        val interval = onNodeWithTag("photobasePushIntervalRow").getUnclippedBoundsInRoot().top
        val backup = onNodeWithTag(BACKUP_TAG).getUnclippedBoundsInRoot().top
        assertTrue(icon < interval && interval < backup, "icon=$icon interval=$interval backup=$backup")
    }

    private companion object {
        const val BACKUP_TAG = "backupSection"
    }
}
