package id.homebase.core.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import id.homebase.photobase.backup.BackupFailure
import id.homebase.photobase.backup.BackupStatus
import id.homebase.photobase.backup.BackupWork
import id.homebase.photobase.backup.BackupWorkStage
import id.homebase.photobase.ui.BackupStatusContent
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BackupStatusContentTest {
    private var retries = 0

    private fun ComposeUiTest.content(status: BackupStatus, backupEnabled: Boolean = true) = setContent {
        MaterialTheme { BackupStatusContent(status, backupEnabled, onRetry = { retries++ }) }
    }

    @Test
    fun turnedOffSaysSoEvenWithNothingLeftToDo() = runComposeUiTest {
        content(BackupStatus(backedUpCount = 3), backupEnabled = false)
        waitUntilExactlyOneExists(hasText("Backup is off"))
    }

    @Test
    fun turnedOnWithNothingLeftSaysAllBackedUp() = runComposeUiTest {
        content(BackupStatus(backedUpCount = 3))
        waitUntilExactlyOneExists(hasText("All photos backed up"))
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
    fun aVideoBeingPreparedShowsWhatAndHowFar() = runComposeUiTest {
        content(BackupStatus(isRunning = true, pendingCount = 1, working = listOf(BackupWork(true, BackupWorkStage.Preparing, 0.37f))))
        waitUntilExactlyOneExists(hasText("Preparing a video"))
        waitUntilExactlyOneExists(hasText("37%"))
    }

    @Test
    fun nothingStartedHasNoWorkSection() = runComposeUiTest {
        content(BackupStatus(pendingCount = 2))
        onNodeWithTag("backupStatusWork").assertDoesNotExist()
    }

    @Test
    fun failuresOfferARetry() = runComposeUiTest {
        content(BackupStatus(failedCount = 1, failures = listOf(BackupFailure("asset", "reason"))))
        onNodeWithTag("backupStatusRetry").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, retries)
    }
}
