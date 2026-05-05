package id.homebase.core.updater

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

class IOSUpdateAppManager(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
): UpdateAppManager {

    // Note, update check will not work for dev version unless its a public available app
    private val appId: String by lazy {
        val bundleId = NSBundle.mainBundle.bundleIdentifier
        when (bundleId) {
            "id.homebase.feed" -> "6468971238"
            "id.homebase.feed.dev" -> "6761429030"
            else -> "6468971238" // default fallback
        }
    }

    private val currentVersion: String by lazy {
        NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String ?: "0.0.0"
    }

    override suspend fun checkForUpdate(): UpdateAppModel {
        return try {
            Logger.d { "Checking App Store for updates. Current version: $currentVersion, App ID: $appId" }

            val response = httpClient.get("https://itunes.apple.com/lookup?id=$appId")
            val responseText = response.body<String>()

            val lookupResponse = json.decodeFromString<AppStoreLookupResponse>(responseText)

            if (lookupResponse.resultCount == 0 || lookupResponse.results.isEmpty()) {
                Logger.w { "No results found for App ID: $appId" }
                return UpdateAppModel(
                    updateAvailable = false,
                    error = UpdateAppError.LATEST_VERSION_NOT_AVAILABLE
                )
            }

            val appStoreVersion = lookupResponse.results.first().version
            val isUpdateAvailable = isNewerVersion(appStoreVersion, currentVersion)

            Logger.i {
                "Version check complete. Current: $currentVersion, App Store: $appStoreVersion, " +
                        "Update available: $isUpdateAvailable"
            }

            UpdateAppModel(
                updateAvailable = isUpdateAvailable,
                canUpdate = true,
                versionName = appStoreVersion,
                error = null
            )
        } catch (e: Exception) {
            Logger.e(e) { "Error checking for updates: ${e.message}" }
            UpdateAppModel(
                updateAvailable = false,
                canUpdate = false,
                error = UpdateAppError.UNKNOWN_ERROR,
            )
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun downloadUpdate(): UpdateResult {
        return try {
            // Open the app's App Store page where users can manually update
            val appStoreUrl = NSURL.URLWithString("itms-apps://apps.apple.com/app/id$appId")

            if (appStoreUrl != null && UIApplication.sharedApplication.canOpenURL(appStoreUrl)) {
                UIApplication.sharedApplication.openURL(
                    url = appStoreUrl,
                    options = emptyMap<Any?, Any>(),
                    completionHandler = { success ->
                        if (success) {
                            Logger.i { "Successfully opened App Store for manual update" }
                        } else {
                            Logger.w { "Failed to open App Store URL" }
                        }
                    }
                )
                UpdateResult.Started
            } else {
                Logger.w { "Could not open App Store URL" }
                UpdateResult.Error(UpdateAppError.UNKNOWN_ERROR)
            }
        } catch (e: Exception) {
            Logger.e(e) { "Error opening App Store: ${e.message}" }
            UpdateResult.Error(UpdateAppError.UNKNOWN_ERROR)
        }
    }


    /**
     * Compares two semantic versions (e.g., "1.2.3" vs "1.2.4").
     * Returns true if appStoreVersion is newer than currentVersion.
     */
    private fun isNewerVersion(appStoreVersion: String, currentVersion: String): Boolean {
        try {
            val appStoreParts = appStoreVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }

            val maxLength = maxOf(appStoreParts.size, currentParts.size)

            for (i in 0 until maxLength) {
                val appStoreNum = appStoreParts.getOrElse(i) { 0 }
                val currentNum = currentParts.getOrElse(i) { 0 }

                if (appStoreNum > currentNum) return true
                if (appStoreNum < currentNum) return false
            }

            return false // Versions are equal
        } catch (e: Exception) {
            Logger.e(e) { "Error comparing versions: $appStoreVersion vs $currentVersion" }
            return false
        }
    }
}

@Serializable
private data class AppStoreLookupResponse(
    @SerialName("resultCount") val resultCount: Int,
    @SerialName("results") val results: List<AppStoreResult>
)

@Serializable
private data class AppStoreResult(
    @SerialName("version") val version: String,
    @SerialName("trackName") val trackName: String? = null,
    @SerialName("bundleId") val bundleId: String? = null,
    @SerialName("minimumOsVersion") val minimumOsVersion: String? = null
)