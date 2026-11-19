package id.homebase.photobase.ui

import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.photobase.backup.BackupSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PhotobaseSetupStepTest {
    @Test
    fun aNewInstallConfirmsQualityBeforePhotosOpens() {
        assertEquals(PhotobaseSetupStep.ConfirmQuality, PhotobaseSetupUiState(installed = true, qualityUnconfirmed = true).step)
    }

    @Test
    fun anInstallWithNothingToConfirmOpensPhotos() {
        assertEquals(PhotobaseSetupStep.Installed, PhotobaseSetupUiState(installed = true).step)
    }

    @Test
    fun notInstalledShowsOnboardingEvenWithQualityPending() {
        assertEquals(PhotobaseSetupStep.Onboarding, PhotobaseSetupUiState(installed = false, qualityUnconfirmed = true).step)
    }

    @Test
    fun backFromTheOwnerConsoleShowsFinishingNotOnboarding() {
        assertEquals(PhotobaseSetupStep.Finishing, PhotobaseSetupUiState(installed = false, finishing = true).step)
    }

    @Test
    fun theRecheckFindingTheInstallLeavesFinishing() {
        assertEquals(
            PhotobaseSetupStep.ConfirmQuality,
            PhotobaseSetupUiState(installed = true, finishing = true, qualityUnconfirmed = true).step,
        )
    }

    @Test
    fun settingsSavedBeforeTheFlagExistedNeverAsk() {
        val saved = """{"enabled":true,"photoQuality":"hd","videoQuality":"standard"}"""
        assertFalse(OdinSystemSerializer.deserialize<BackupSettings>(saved).qualityUnconfirmed)
    }
}
