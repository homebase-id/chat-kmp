package id.homebase.photobase.backup

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import co.touchlab.kermit.Logger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class AndroidBackupTriggers(private val context: Context) : BackupTriggers {
    private val workManager get() = WorkManager.getInstance(context)

    override fun rearm(settings: BackupSettings) {
        if (!settings.enabled) {
            ALL_WORK.forEach(workManager::cancelUniqueWork)
            context.getSystemService(JobScheduler::class.java).cancel(USER_INITIATED_JOB_ID)
            return
        }
        // Content triggers fire once and don't survive every process death: every run and every process start re-arms this one.
        workManager.enqueueUniqueWork(MEDIA_CHANGED_WORK, ExistingWorkPolicy.KEEP, mediaChangedRequest(settings))
        val periodic = PeriodicWorkRequestBuilder<PhotoBackupWorker>(SAFETY_NET_PERIOD.toJavaDuration())
            .setConstraints(constraints(settings))
            .setInputData(workDataOf(PhotoBackupWorker.KEY_TRIGGER to BackupTrigger.Periodic.name))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FIRST_RETRY.inWholeMinutes, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        val overnight = PeriodicWorkRequestBuilder<PhotoBackupWorker>(OVERNIGHT_PERIOD.toJavaDuration())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .build(),
            )
            .setInputData(workDataOf(PhotoBackupWorker.KEY_TRIGGER to BackupTrigger.Overnight.name))
            .build()
        workManager.enqueueUniquePeriodicWork(OVERNIGHT_WORK, ExistingPeriodicWorkPolicy.UPDATE, overnight)
    }

    override suspend fun onWake(remaining: Duration, run: suspend (BackupRunRequest) -> Unit) {
        val request = OneTimeWorkRequestBuilder<PhotoBackupWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(PhotoBackupWorker.KEY_TRIGGER to BackupTrigger.Push.name))
            .build()
        workManager.enqueueUniqueWork(PUSH_WORK, ExistingWorkPolicy.KEEP, request)
    }

    override fun startUserRequestedRun(settings: BackupSettings, progress: Flow<Float>, runInApp: suspend () -> Unit): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val network = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .apply { if (settings.wifiOnly) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
                .build()
            val job = JobInfo.Builder(USER_INITIATED_JOB_ID, ComponentName(context, PhotoBackupJobService::class.java))
                .setUserInitiated(true)
                .setRequiredNetwork(network)
                .build()
            val scheduled = context.getSystemService(JobScheduler::class.java).schedule(job)
            if (scheduled != JobScheduler.RESULT_SUCCESS) Logger.w(tag = TAG) { "user-initiated job refused: $scheduled" }
            return scheduled == JobScheduler.RESULT_SUCCESS
        }
        val request = OneTimeWorkRequestBuilder<PhotoBackupWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(constraints(settings))
            .setInputData(workDataOf(PhotoBackupWorker.KEY_TRIGGER to BackupTrigger.UserRequested.name))
            .build()
        workManager.enqueueUniqueWork(USER_REQUESTED_WORK, ExistingWorkPolicy.KEEP, request)
        return true
    }

    override fun batteryGuidance(): BatteryGuidance {
        val power = context.getSystemService(PowerManager::class.java)
        return BatteryGuidance(
            isRestricted = !power.isIgnoringBatteryOptimizations(context.packageName),
            openSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
    }

    private fun mediaChangedRequest(settings: BackupSettings): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType(settings))
            .setRequiresCharging(settings.chargingOnly)
            .setRequiresBatteryNotLow(true)
            .setTriggerContentUpdateDelay(MEDIA_UPDATE_DELAY.toJavaDuration())
            .setTriggerContentMaxDelay(MEDIA_MAX_DELAY.toJavaDuration())
        for (uri in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.INTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.INTERNAL_CONTENT_URI,
        )) {
            constraints.addContentUriTrigger(uri, true)
        }
        return OneTimeWorkRequestBuilder<PhotoBackupWorker>()
            .setConstraints(constraints.build())
            .setInputData(workDataOf(PhotoBackupWorker.KEY_TRIGGER to BackupTrigger.MediaChanged.name))
            .build()
    }

    private fun constraints(settings: BackupSettings) = Constraints.Builder()
        .setRequiredNetworkType(networkType(settings))
        .setRequiresCharging(settings.chargingOnly)
        .setRequiresBatteryNotLow(true)
        .build()

    // Declining from app code would wake the phone just to say no.
    private fun networkType(settings: BackupSettings) = if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

    private companion object {
        const val TAG = "AndroidBackupTriggers"
        const val MEDIA_CHANGED_WORK = "photobase-media-changed"
        const val PERIODIC_WORK = "photobase-periodic"
        const val OVERNIGHT_WORK = "photobase-overnight"
        const val PUSH_WORK = "photobase-push"
        const val USER_REQUESTED_WORK = "photobase-user-requested"
        val ALL_WORK = listOf(MEDIA_CHANGED_WORK, PERIODIC_WORK, OVERNIGHT_WORK, PUSH_WORK, USER_REQUESTED_WORK)
        const val USER_INITIATED_JOB_ID = 72011
        val SAFETY_NET_PERIOD = 1.hours
        val OVERNIGHT_PERIOD = 8.hours
        val FIRST_RETRY = 1.minutes
        val MEDIA_UPDATE_DELAY = 30.seconds
        val MEDIA_MAX_DELAY = 5.minutes
    }
}
