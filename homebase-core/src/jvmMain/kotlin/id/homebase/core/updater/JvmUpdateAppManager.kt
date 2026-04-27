package id.homebase.core.updater

import co.touchlab.kermit.Logger
import dev.hydraulic.conveyor.control.SoftwareUpdateController
import dev.hydraulic.conveyor.control.SoftwareUpdateController.UpdateCheckException
import id.homebase.api.file.JvmFileSystemUtil
import id.homebase.core.util.PlatformInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skiko.hostOs
import java.util.Properties

class JvmUpdateAppManager(
    private val httpClient: HttpClient,
    private val platformInfo: PlatformInfo,
): UpdateAppManager {

    private val controller: SoftwareUpdateController? = SoftwareUpdateController.getInstance()

    private val githubRepo: String by lazy {
        if (JvmFileSystemUtil.isProductionVersion()) "chat-desktop-release-production" else "chat-desktop-release-bleeding"
    }

    override suspend fun checkForUpdate(): UpdateAppModel {
        try {
            if (hostOs.isLinux) {
                return checkForUpdateLinux()
            }

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

            // TODO - add support for Linux


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

    private suspend fun checkForUpdateLinux(): UpdateAppModel = withContext(Dispatchers.IO) {
        try {
            val url = "https://github.com/homebase-id/$githubRepo/releases/latest/download/metadata.properties"

            // Use Ktor HTTP client (already in your project)
            val response = httpClient.get(url).bodyAsText()
            httpClient.close()

            // Parse properties
            val properties = Properties()
            properties.load(response.byteInputStream())
            val remoteVersion = properties.getProperty("app.version") ?: return@withContext UpdateAppModel(
                updateAvailable = false,
                error = UpdateAppError.LATEST_VERSION_NOT_AVAILABLE
            )

            // Get current version
            val currentVersion = platformInfo.versionName

            val updateAvailable = compareVersions(remoteVersion, currentVersion) > 0

            UpdateAppModel(
                updateAvailable = updateAvailable,
                canUpdate = updateAvailable, // User can run apt commands
                versionName = remoteVersion
            )
        } catch (e: Exception) {
            Logger.e(e) { "Failed to check Linux updates: ${e.message}" }
            UpdateAppModel(updateAvailable = false, error = UpdateAppError.UNKNOWN_ERROR)
        }
    }

    private fun compareVersions(remote: String, current: String): Int {
        val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r != c) return r.compareTo(c)
        }
        return 0
    }
}