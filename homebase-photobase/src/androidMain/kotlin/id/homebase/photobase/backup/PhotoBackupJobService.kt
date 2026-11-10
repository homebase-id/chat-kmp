package id.homebase.photobase.backup

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import co.touchlab.kermit.Logger
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.api.coroutines.supervisedScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.time.Duration.Companion.seconds

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
            val controller = get<PhotoBackupController>()
            val reporter = launch { reportProgress(params, controller) }
            var failed = false
            try {
                controller.runFromBackground(
                    BackupRunRequest(BackupTrigger.UserRequested, BackupBudget(null)),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(tag = TAG, throwable = e) { "user-initiated backup failed" }
                failed = true
            } finally {
                reporter.cancel()
            }
            jobFinished(params, failed)
        }
        return true
    }

    /** The system judges a long user-initiated job by these. Bytes count from this job's start, not the whole library. */
    private suspend fun reportProgress(params: JobParameters, controller: PhotoBackupController) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val alreadyBackedUp = controller.freshStatus()?.backedUpBytes ?: 0
        val status = controller.status ?: return
        status.collect {
            val sent = ((it.backedUpBytes ?: 0) - alreadyBackedUp).coerceAtLeast(0)
            updateEstimatedNetworkBytes(params, 0, sent + (it.remainingBytes ?: 0))
            updateTransferredNetworkBytes(params, 0, sent)
            setNotification(params, BACKUP_NOTIFICATION_ID, backupNotification(this, it.progress), JOB_END_NOTIFICATION_POLICY_REMOVE)
            delay(PROGRESS_GAP)
        }
    }

    /** The system's timeout included: nothing is lost, every row stays where a later run picks it up. */
    override fun onStopJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Logger.i(tag = TAG) { "stopped by the OS: reason=${params.stopReason}" }
        job?.cancel()
        return true
    }

    private companion object {
        const val TAG = "PhotoBackupJobService"
        val PROGRESS_GAP = 2.seconds
    }
}
