package id.homebase.core.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.photobase.backup.BackupFailure
import id.homebase.photobase.backup.BackupStatus
import id.homebase.photobase.ui.BackupStatusContent
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BackupStatusContentTest {
    private var retries = 0

    private fun ComposeUiTest.content(status: BackupStatus) = setContent {
        MaterialTheme { BackupStatusContent(status, backupEnabled = true, onRetry = { retries++ }) }
    }

    @Test
    fun allDoneHasNoProgressBarOrFailures() = runComposeUiTest {
        content(BackupStatus(backedUpCount = 3))
        onNodeWithTag("backupStatusHero").assertExists()
        onNodeWithTag("backupStatusProgress").assertDoesNotExist()
        onNodeWithTag("backupStatusFailures").assertDoesNotExist()
    }

    @Test
    fun workLeftShowsTheProgressBar() = runComposeUiTest {
        content(BackupStatus(backedUpCount = 3, pendingCount = 2))
        onNodeWithTag("backupStatusProgress", useUnmergedTree = true).assertExists()
    }

    @Test
    fun failuresOfferARetry() = runComposeUiTest {
        content(BackupStatus(failedCount = 1, failures = listOf(BackupFailure("asset", "reason"))))
        onNodeWithTag("backupStatusRetry").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, retries)
    }
}
