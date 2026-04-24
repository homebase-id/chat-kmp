package id.homebase.core.updater

import co.touchlab.kermit.Logger
import dev.hydraulic.conveyor.control.SoftwareUpdateController
import dev.hydraulic.conveyor.control.SoftwareUpdateController.UpdateCheckException

class JvmUpdateAppManager: UpdateAppManager {

    private val controller: SoftwareUpdateController? = SoftwareUpdateController.getInstance()

    override suspend fun checkForUpdate(): UpdateAppModel {
        try {
            if (controller == null) {
                Logger.i { "SoftwareUpdateController is not available on this app version." }
                return UpdateAppModel(updateAvailable = false, error = UpdateAppError.UNSUPPORTED_VERSION)
            }

            val currentVersion = controller.currentVersion
            if (currentVersion == null) {
                // Handle the case where current version is not available
                Logger.i { "Current version information is not available." }
                return UpdateAppModel(updateAvailable = false, error = UpdateAppError.CURRENT_VERSION_NOT_AVAILABLE)
            }

            val latestVersion = controller.currentVersionFromRepository
            if (latestVersion == null) {
                // Handle the case where latest version information is not available
                Logger.i { "Latest version information is not available." }
                return UpdateAppModel(updateAvailable = false, error = UpdateAppError.LATEST_VERSION_NOT_AVAILABLE)
            }

            if (latestVersion > currentVersion) {
                // A newer version is available
                val canUpdate = controller.canTriggerUpdateCheckUI() == SoftwareUpdateController.Availability.AVAILABLE
                Logger.i { "Update available! Current version: $currentVersion, Latest version: $latestVersion, Can update: $canUpdate" }
                return UpdateAppModel(updateAvailable = true, canUpdate = canUpdate, versionName = latestVersion.version)
            } else {
                // No update available or current version is newer
                Logger.i { "No update available. Current version: $currentVersion, Latest version: $latestVersion" }
                return UpdateAppModel(updateAvailable = false, versionName = currentVersion.version)
            }
        } catch (e: UpdateCheckException) {
            Logger.e(e) { "Error checking for updates: ${e.message}" }
            return UpdateAppModel(updateAvailable = false, error = UpdateAppError.UNKNOWN_ERROR)
        }
    }


    override suspend fun downloadUpdate(): UpdateResult {
        return try {
            if (controller == null) {
                Logger.w { "SoftwareUpdateController not available" }
                return UpdateResult.Unsupported
            }

            if (controller.canTriggerUpdateCheckUI() != SoftwareUpdateController.Availability.AVAILABLE) {
                Logger.w { "Cannot trigger update UI" }
                return UpdateResult.NoUpdateAvailable
            }

            // Conveyor shows its own UI dialog and handles the update
            controller.triggerUpdateCheckUI()
            Logger.i { "Update UI triggered" }
            UpdateResult.Started
        } catch (e: Exception) {
            Logger.e(e) { "Error triggering update: ${e.message}" }
            UpdateResult.Error(UpdateAppError.UNKNOWN_ERROR)
        }
    }
}