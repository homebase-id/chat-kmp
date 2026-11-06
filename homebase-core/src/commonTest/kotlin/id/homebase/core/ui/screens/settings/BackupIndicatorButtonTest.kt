package id.homebase.core.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.photobase.backup.BackupPauseReason
import id.homebase.photobase.backup.BackupStatus
import id.homebase.photobase.ui.BackupIndicatorButton
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BackupIndicatorButtonTest {
    private var taps = 0

    private fun ComposeUiTest.button(status: BackupStatus, backupEnabled: Boolean = true) = setContent {
        MaterialTheme { BackupIndicatorButton(status, backupEnabled, onClick = { taps++ }) }
    }

    @Test
    fun aRunShowsTheRing() = runComposeUiTest {
        button(BackupStatus(isRunning = true))
        onNodeWithTag("backupIndicatorWorking", useUnmergedTree = true).assertExists()
    }

    @Test
    fun doneShowsTheCheck() = runComposeUiTest {
        button(BackupStatus(backedUpCount = 3))
        onNodeWithTag("backupIndicatorUpToDate", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aFullDriveShowsTheCaution() = runComposeUiTest {
        button(BackupStatus(pauseReason = BackupPauseReason.StorageFull))
        onNodeWithTag("backupIndicatorAttention", useUnmergedTree = true).assertExists()
    }

    @Test
    fun backupOffShowsNothing() = runComposeUiTest {
        button(BackupStatus(backedUpCount = 3), backupEnabled = false)
        onNodeWithTag("backupIndicator").assertDoesNotExist()
    }

    @Test
    fun tappingOpensStatus() = runComposeUiTest {
        button(BackupStatus(backedUpCount = 3))
        onNodeWithTag("backupIndicator").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, taps)
    }
}
