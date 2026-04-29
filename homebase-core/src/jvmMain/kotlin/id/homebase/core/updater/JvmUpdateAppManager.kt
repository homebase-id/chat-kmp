package id.homebase.core.updater

import co.touchlab.kermit.Logger
import dev.hydraulic.conveyor.control.SoftwareUpdateController
import dev.hydraulic.conveyor.control.SoftwareUpdateController.UpdateCheckException
import id.homebase.api.file.JvmFileSystemUtil
import id.homebase.api.file.JvmFileSystemUtil.isProductionVersion
import id.homebase.core.util.PlatformInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skiko.hostOs
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

class JvmUpdateAppManager(
    private val httpClient: HttpClient,
    private val platformInfo: PlatformInfo,
): UpdateAppManager {

    private val controller: SoftwareUpdateController? = SoftwareUpdateController.getInstance()

    private val githubRepo: String by lazy {
        if (isProductionVersion()) "chat-desktop-release-production" else "chat-desktop-release-bleeding"
    }

    override suspend fun checkForUpdate(): UpdateAppModel {
        try {
            if (hostOs.isLinux) {
                val canUpdate = isDebianPackageInstalled()
                return checkForUpdateDirectly(canUpdate)
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
            if (hostOs.isLinux) {
                // Check if installed via .deb
                return if (isDebianPackageInstalled()) {
                    // Use apt to update from configured repository
                    updateFromAptRepository()
                } else {
                    // Manually downloaded/appimage - could download .deb
                    UpdateResult.Unsupported
                }
            }

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

    private suspend fun checkForUpdateDirectly(canUpdate: Boolean): UpdateAppModel = withContext(Dispatchers.IO) {
        try {
            val url = "https://github.com/homebase-id/$githubRepo/releases/latest/download/metadata.properties"

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
                canUpdate = canUpdate,
                versionName = remoteVersion
            )
        } catch (e: Exception) {
            Logger.e(e) { "Failed to check Linux updates: ${e.message}" }
            UpdateAppModel(updateAvailable = false, error = UpdateAppError.UNKNOWN_ERROR)
        }
    }

    private fun isDebianPackageInstalled(): Boolean {
        return try {
            val packageName = getLinuxLauncherCommand()

            // Method 1: Check dpkg status file
            val dpkgStatusFile = File("/var/lib/dpkg/info/$packageName.list")
            if (dpkgStatusFile.exists()) return true

            // Method 2: Query dpkg database
            val process = ProcessBuilder("dpkg", "-s", packageName)
                .redirectErrorStream(true)
                .start()

            process.waitFor(5, TimeUnit.SECONDS)
            val isInstalled = process.exitValue() == 0

            if (isInstalled) {
                Logger.i { "Package $packageName is installed via dpkg" }
                return true
            }

            // Method 3: Check installation path
            val jarLocation = JvmFileSystemUtil::class.java.protectionDomain.codeSource.location.path
            val isSystemPath = jarLocation.startsWith("/usr/share/") || jarLocation.startsWith("/opt/")

            if (isSystemPath) {
                Logger.i { "Running from system path: $jarLocation" }
            }

            isSystemPath
        } catch (e: Exception) {
            Logger.w(e) { "Error checking package installation: ${e.message}" }
            false
        }
    }

    /**
     * Updates the package using apt from the configured repository.
     * Assumes the PPA/repository is already added to apt sources.
     */
    private suspend fun updateFromAptRepository(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            Logger.i { "Starting apt repository update..." }

            val packageName = getLinuxLauncherCommand()

            // Step 1: Refresh package lists
            val updateSuccess = if (hasProgramInPath("pkexec") && hasProgramInPath("apt")) {
                Logger.i { "Refreshing apt package lists with pkexec..." }
                executeCommand(
                    listOf("pkexec", "apt", "update"),
                    timeoutMinutes = 2
                )
            } else if (hasProgramInPath("sudo") && hasProgramInPath("apt")) {
                Logger.i { "Refreshing apt package lists with sudo..." }
                executeCommand(
                    listOf("sudo", "-A", "apt", "update"),
                    timeoutMinutes = 2
                )
            } else {
                Logger.w { "No privilege escalation method available" }
                return@withContext UpdateResult.Unsupported
            }

            if (!updateSuccess) {
                Logger.w { "Failed to update package lists" }
                return@withContext UpdateResult.Error(UpdateAppError.UNKNOWN_ERROR)
            }

            // Step 2: Upgrade the specific package
            val upgradeSuccess = if (hasProgramInPath("pkexec") && hasProgramInPath("apt")) {
                Logger.i { "Upgrading $packageName with pkexec..." }
                executeCommand(
                    listOf(
                        "pkexec",
                        "apt", "install", "-y",
                        "--only-upgrade",  // Only upgrade if already installed
                        packageName
                    ),
                    timeoutMinutes = 5
                )
            } else if (hasProgramInPath("sudo") && hasProgramInPath("apt")) {
                Logger.i { "Upgrading $packageName with sudo..." }
                executeCommand(
                    listOf(
                        "sudo", "-A",
                        "apt", "install", "-y",
                        "--only-upgrade",
                        packageName
                    ),
                    timeoutMinutes = 5
                )
            } else {
                false
            }

            if (upgradeSuccess) {
                Logger.i { "Package upgraded successfully from repository" }
                // Restart the application
                restartApplication()

                // If restart is async, return Completed
                UpdateResult.Completed
            } else {
                Logger.w { "Failed to upgrade package" }
                UpdateResult.Error(UpdateAppError.UNKNOWN_ERROR)
            }
        } catch (e: Exception) {
            Logger.e(e) { "Error during apt repository update: ${e.message}" }
            UpdateResult.Error(UpdateAppError.UNKNOWN_ERROR)
        }
    }

    private fun executeCommand(
        command: List<String>,
        timeoutMinutes: Long = 2
    ): Boolean {
        return try {
            Logger.i { "Executing: ${command.joinToString(" ")}" }

            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(true)

            val process = processBuilder.start()

            // Capture output
            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    output.appendLine(line)
                    Logger.d { "[apt] $line" }
                }
            }

            val completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)

            if (!completed) {
                Logger.w { "Process timed out after $timeoutMinutes minutes" }
                process.destroyForcibly()
                return false
            }

            val exitCode = process.exitValue()
            Logger.i { "Process exited with code: $exitCode" }

            if (exitCode != 0) {
                Logger.w { "Process failed. Output:\n$output" }
            }

            exitCode == 0
        } catch (e: Exception) {
            Logger.e(e) { "Error executing command: ${e.message}" }
            false
        }
    }

    private fun hasProgramInPath(program: String): Boolean {
        return try {
            val process = ProcessBuilder("which", program)
                .redirectErrorStream(true)
                .start()

            process.waitFor(5, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun restartApplication() {
        try {
            Logger.i { "Initiating application restart..." }

            val packageName = getLinuxLauncherCommand()

            // Check if running from installed location
            val jarLocation = JvmUpdateAppManager::class.java.protectionDomain.codeSource.location.path
            val isInstalled = jarLocation.startsWith("/usr/share/") || jarLocation.startsWith("/opt/")

            if (isInstalled) {
                // If installed as .deb, use the system launcher command
                // The .deb package installs a launcher script (e.g., /usr/bin/homebase-chat)
                // that uses the bundled JRE
                Logger.i { "Using system launcher for $packageName" }
                ProcessBuilder(packageName).start()
            } else {
                // Running from development/manual JAR - use current JRE
                val javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
                val currentJar = File(JvmUpdateAppManager::class.java.protectionDomain.codeSource.location.toURI())

                Logger.i { "Using JAR launcher with current JRE" }
                val command = listOf(javaBin, "-jar", currentJar.absolutePath)
                ProcessBuilder(command).start()
            }

            // Give the new instance a moment to start
            Thread.sleep(500)

            // Exit current instance
            Logger.i { "Exiting current instance..." }
            kotlin.system.exitProcess(0)

        } catch (e: Exception) {
            Logger.e(e) { "Failed to restart application: ${e.message}" }
            // If restart fails, just exit and let user manually restart
            kotlin.system.exitProcess(0)
        }
    }

    /**
     * Gets the Linux launcher command name.
     * Conveyor uses the display-name converted to lowercase-with-hyphens.
     */
    private fun getLinuxLauncherCommand(): String {
        // Use the same logic as JvmFileSystemUtil
        return if (isProductionVersion()) {
            "homebase-chat"
        } else {
            "homebase-chat-dev"
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