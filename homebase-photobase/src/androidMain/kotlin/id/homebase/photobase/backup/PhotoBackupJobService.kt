package id.homebase.photobase.backup

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import co.touchlab.kermit.Logger
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.api.coroutines.supervisedScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/** "Back up now" on API 34+: a user-initiated data transfer job, which outlasts WorkManager's 10 minutes. */
class PhotoBackupJobService : JobService(), KoinComponent {
    private val scope = supervisedScope("photo-backup-job", ioDispatcher)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = scope.launch {
            // Only ever scheduled on API 34+; the check is for the manifest, which lint reads alone.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setNotification(params, BACKUP_NOTIFICATION_ID, backupNotification(this@PhotoBackupJobService), JOB_END_NOTIFICATION_POLICY_REMOVE)
            }
            var failed = false
            try {
                get<PhotoBackupController>().runFromBackground(
                    BackupRunRequest(BackupTrigger.UserRequested, BackupBudget(null), findDeletions = true),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(tag = TAG, throwable = e) { "user-initiated backup failed" }
                failed = true
            }
            jobFinished(params, failed)
        }
        return true
    }

    /** The system's timeout included: nothing is lost, every row stays where a later run picks it up. */
    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    private companion object {
        const val TAG = "PhotoBackupJobService"
    }
}
