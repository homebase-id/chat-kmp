package id.homebase.feed

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import id.homebase.core.notifications.NotificationEntry
import id.homebase.core.sync.SyncOutcome
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class DriveSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params), KoinComponent {

    override suspend fun doWork(): Result {
        Logger.i(tag = "DriveSyncWorker") { "doWork: starting (attempt=$runAttemptCount)" }
        val title = inputData.getString(DriveFcmService.KEY_TITLE)
        val body = inputData.getString(DriveFcmService.KEY_BODY)
        val data = inputData.keyValueMap
            .filterKeys { it.startsWith(DriveFcmService.KEY_DATA_PREFIX) }
            .mapKeys { (k, _) -> k.removePrefix(DriveFcmService.KEY_DATA_PREFIX) }
            .mapValues { (_, v) -> v?.toString() ?: "" }

        val entry: NotificationEntry = get()
        return when (val outcome = entry.onPushArrived(title, body, data)) {
            is SyncOutcome.Success -> {
                Logger.i(tag = "DriveSyncWorker") { "doWork: success" }
                Result.success()
            }
            is SyncOutcome.NoCredentials -> {
                Logger.i(tag = "DriveSyncWorker") { "doWork: no credentials" }
                Result.success()
            }
            is SyncOutcome.Failed -> {
                Logger.e(tag = "DriveSyncWorker") { "doWork: failed — ${outcome.cause.message}" }
                Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Background Sync", NotificationManager.IMPORTANCE_MIN)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "drive_sync_channel"
        const val NOTIFICATION_ID = 9001
    }
}
