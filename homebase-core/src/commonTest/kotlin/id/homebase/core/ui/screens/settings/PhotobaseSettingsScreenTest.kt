package id.homebase.core.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.photobase.ui.PhotobaseSettingsUiState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PhotobaseSettingsScreenTest {
    private fun ComposeUiTest.page(
        onAction: (SettingsUiAction) -> Unit = {},
        onOpenPhotoBackup: (() -> Unit)?,
    ) = setContent {
        MaterialTheme {
            PhotobaseSettingsUi(SettingsUiState(), onAction, PhotobaseSettingsUiState(), {}, {}, onOpenPhotoBackup)
        }
    }

    @Test
    fun theIconToggleFiresItsAction() = runComposeUiTest {
        val seen = mutableListOf<SettingsUiAction>()
        page(onAction = { seen += it }, onOpenPhotoBackup = null)
        onNodeWithTag("photobaseIconToggle").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf<SettingsUiAction>(SettingsUiAction.SetPhotobaseIconVisible(true)), seen)
    }

    @Test
    fun theIntervalRowIsThereWithoutBackup() = runComposeUiTest {
        page(onOpenPhotoBackup = null)
        onNodeWithTag("photobasePushIntervalRow").assertExists()
    }

    @Test
    fun backupIsOfferedOnlyWhereItExists() = runComposeUiTest {
        var opened = 0
        page(onOpenPhotoBackup = { opened++ })
        onNodeWithTag("photoBackupSettingsButton").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
    }

    @Test
    fun backupIsHiddenWhereItDoesNotExist() = runComposeUiTest {
        page(onOpenPhotoBackup = null)
        onNodeWithTag("photoBackupSettingsButton").assertDoesNotExist()
    }
}
