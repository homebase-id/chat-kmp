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
import kotlin.system.exitProcess

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
                return UpdateAppModel(updateAvailable = false, canUpdate = true, versionName = currentVersion.version)
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
            val packageName = getDebianPackageName()

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
            Logger.w { "Error checking package installation: ${e.message}" }
            false
        }
    }

    private suspend fun updateFromAptRepository(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            Logger.i { "Starting apt repository update process" }
            installLinuxPackage()

            // This won't be reached, but required for return type
            @Suppress("UNREACHABLE_CODE")
            UpdateResult.Started

        } catch (e: Exception) {
            Logger.e(e) { "Error during apt repository update: ${e.message}" }
            UpdateResult.Error(UpdateAppError.UNKNOWN_ERROR)
        }
    }

    private fun installLinuxPackage() {
        val pid = ProcessHandle.current().pid()
        val launcher =
            resolveLinuxLauncher()
                ?: error("Cannot resolve application launcher from java.home")

        val packageName = getDebianPackageName()

        val script = File(System.getProperty("java.io.tmpdir"), "homebase-update.sh")
        script.writeText(
            $$"""
            |#!/usr/bin/env bash
            |
            |# Ignore SIGHUP to survive parent process exit
            |trap '' HUP
            |            
            |# Relaunch the application with GUI/session variables preserved
            |export DISPLAY="${DISPLAY:-:0}"
            |export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-0}"
            |# Keep XDG_RUNTIME_DIR/DBUS if present from parent env            
            |            
            |APP_PID=$$pid
            |APP_LAUNCHER="$${launcher.absolutePath}"
            |
            |# Wait for the app process to fully exit
            |while kill -0 "$APP_PID" 2>/dev/null; do
            |    sleep 0.5
            |done
            |
            |sleep 1
            |
            |# Install the package (shows graphical authentication dialog)
            |# Do not use set -e: dpkg/rpm may return non-zero on warnings,
            |# which would prevent the application from relaunching.
            |pkexec sh -c "apt update && apt install -y --only-upgrade $$packageName"
            |            
            |# Relaunch the application
            |nohup env \
            |  DISPLAY="$DISPLAY" \
            |  WAYLAND_DISPLAY="$WAYLAND_DISPLAY" \
            |  XDG_RUNTIME_DIR="$XDG_RUNTIME_DIR" \
            |  DBUS_SESSION_BUS_ADDRESS="$DBUS_SESSION_BUS_ADDRESS" \
            |  "$APP_LAUNCHER" >/dev/null 2>&1 &
            |# Clean up this script
            |rm -f "$0"
            """.trimMargin(),
        )

        ProcessBuilder("setsid", "bash", script.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        exitProcess(0)
    }

    private fun resolveLinuxLauncher(): File? {
        val javaHome = System.getProperty("java.home") ?: return null
        val appRoot = File(javaHome).parentFile?.parentFile ?: return null
        val binDir = File(appRoot, "bin")
        if (!binDir.isDirectory) return null
        return binDir.listFiles()?.firstOrNull { it.canExecute() }
    }

    private fun getDebianPackageName(): String {
        return "homebase-homebase-chat"
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