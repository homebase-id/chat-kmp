package id.homebase.photobase.backup

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Its own unique work, away from DriveSyncWorker: a backup inside that worker would hold its KEEP
 * slot, and pushes arriving meanwhile would lose their payloads.
 */
class PhotoBackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params), KoinComponent {

    override suspend fun doWork(): Result {
        val trigger = BackupTrigger.valueOf(inputData.getString(KEY_TRIGGER) ?: BackupTrigger.Periodic.name)
        val userRequested = trigger == BackupTrigger.UserRequested
        if (userRequested) setForeground(getForegroundInfo())
        val request = BackupRunRequest(
            trigger = trigger,
            budget = BackupBudget(if (userRequested) null else PhotoBackupRun.WORKER_BUDGET),
            findDeletions = trigger == BackupTrigger.Periodic || trigger == BackupTrigger.Overnight || userRequested,
        )
        return try {
            get<PhotoBackupController>().runFromBackground(request)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "backup run failed; $trigger will retry" }
            Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = backupNotification(applicationContext)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(BACKUP_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(BACKUP_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
        private const val TAG = "PhotoBackupWorker"
    }
}
