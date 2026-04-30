package id.homebase.core.updater

import android.content.Context
import co.touchlab.kermit.Logger
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import id.homebase.api.ActivityProvider

class AndroidUpdateAppManager(
    private val context: Context
): UpdateAppManager {

    private val appUpdateManager by lazy {
        AppUpdateManagerFactory.create(context)
    }

    override suspend fun checkForUpdate(): UpdateAppModel {
        return try {
            val appUpdateInfo = appUpdateManager.requestAppUpdateInfo()

            val updateAvailable = appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && (
                    appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) ||
                            appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                    )

            Logger.i { "Update check: available=$updateAvailable, availableVersionCode=${appUpdateInfo.availableVersionCode()}" }

            UpdateAppModel(
                updateAvailable = updateAvailable,
                canUpdate = true,
                versionName = appUpdateInfo.availableVersionCode().toString(),
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

    override suspend fun downloadUpdate(): UpdateResult {
        return try {
            val activity = ActivityProvider.getActivity()
            if (activity == null) {
                Logger.w { "Activity not available for update" }
                return UpdateResult.Error(UpdateAppError.UNKNOWN_ERROR)
            }

            val appUpdateInfo = appUpdateManager.requestAppUpdateInfo()

            if (appUpdateInfo.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) {
                Logger.i { "No update available" }
                return UpdateResult.NoUpdateAvailable
            }

            // Prefer immediate update for critical updates, fall back to flexible
            val updateType = when {
                appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
                appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> AppUpdateType.FLEXIBLE
                else -> {
                    Logger.w { "No allowed update type" }
                    return UpdateResult.NoUpdateAvailable
                }
            }

            appUpdateManager.startUpdateFlow(
                appUpdateInfo,
                activity,
                AppUpdateOptions.defaultOptions(updateType)
            )

            Logger.d { "Update flow started with type=$updateType" }
            UpdateResult.Started
        } catch (e: Exception) {
            Logger.e(e) { "Error starting update: ${e.message}" }
            UpdateResult.Error(UpdateAppError.UNKNOWN_ERROR)
        }
    }
}