package id.homebase.photobase.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackupIndicatorTest {
    private val done = BackupStatus(backedUpCount = 5, lastSuccessMs = 1)

    @Test
    fun aRunOrUploadsStillGoingAreWorking() {
        assertEquals(BackupIndicator.Working, backupIndicatorOf(BackupStatus(isRunning = true), backupEnabled = true))
        assertEquals(BackupIndicator.Working, backupIndicatorOf(done.copy(uploadingCount = 1), backupEnabled = true))
    }

    @Test
    fun onlyNothingLeftCountsAsDone() {
        assertEquals(BackupIndicator.UpToDate, backupIndicatorOf(done, backupEnabled = true))
        assertEquals(BackupIndicator.Paused, backupIndicatorOf(done.copy(pendingCount = 1), backupEnabled = true))
        assertEquals(BackupIndicator.NeedsAttention, backupIndicatorOf(done.copy(failedCount = 1), backupEnabled = true))
    }

    @Test
    fun nothingBackedUpYetIsNotDone() {
        assertNull(backupIndicatorOf(BackupStatus(), backupEnabled = true))
    }

    @Test
    fun whatResumesByItselfIsPausedAndWhatDoesNotNeedsTheUser() {
        assertEquals(BackupIndicator.Paused, backupIndicatorOf(BackupStatus(pauseReason = BackupPauseReason.WaitingForWifi), backupEnabled = true))
        assertEquals(BackupIndicator.NeedsAttention, backupIndicatorOf(BackupStatus(pauseReason = BackupPauseReason.StorageFull), backupEnabled = true))
        assertEquals(BackupIndicator.NeedsAttention, backupIndicatorOf(BackupStatus(pauseReason = BackupPauseReason.NoLibraryAccess), backupEnabled = true))
    }

    @Test
    fun aFailedPhotoWaitsUntilTheRunEnds() {
        assertEquals(BackupIndicator.Working, backupIndicatorOf(BackupStatus(isRunning = true, failedCount = 1), backupEnabled = true))
    }

    @Test
    fun backupOffOrSignedOutShowsNothing() {
        assertNull(backupIndicatorOf(done, backupEnabled = false))
        assertNull(backupIndicatorOf(BackupStatus(pauseReason = BackupPauseReason.SignedOut), backupEnabled = true))
    }
}
