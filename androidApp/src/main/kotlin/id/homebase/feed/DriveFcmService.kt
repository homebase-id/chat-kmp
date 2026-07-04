package id.homebase.feed

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import co.touchlab.kermit.Logger
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import id.homebase.core.notifications.NotificationService
import org.koin.android.ext.android.inject

class DriveFcmService : FirebaseMessagingService() {

    private val notificationService: NotificationService by inject()

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Forward token to KMPNotifier listeners (including NotificationService)
        notificationService.onNewFcmToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Hop 1 of the push→capture chain (#988). priority vs originalPriority exposes an
        // FCM/Doze downgrade (high requested, normal delivered = deferred delivery, #986).
        // Kermit is initialized in MainApplication.onCreate, which always precedes service
        // callbacks in this process.
        Logger.i(tag = "PushCapture") {
            val downgraded = if (message.priority != message.originalPriority) " (DOWNGRADED)" else ""
            "received: priority=${pri(message.priority)} originalPriority=${pri(message.originalPriority)}" +
                "$downgraded dataKeys=${message.data.size} notif=${message.notification != null}"
        }

        // Hand the FCM payload to the shared NotificationEntry via a worker —
        // the worker runs the same onPushArrived(...) body iOS calls inline,
        // and WorkManager gives us OS-budgeted background guarantees that
        // FCM's onMessageReceived callback by itself does not.
        val inputData = Data.Builder().apply {
            putString(KEY_TITLE, message.notification?.title)
            putString(KEY_BODY, message.notification?.body)
            putLong(KEY_ENQUEUED_AT_MS, System.currentTimeMillis())
            for ((k, v) in message.data) putString("$KEY_DATA_PREFIX$k", v)
        }.build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                WORK_TAG,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DriveSyncWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setInputData(inputData)
                    .build()
            )
        // KEEP policy: a `received:`+`enqueue:` pair with NO subsequent "doWork: starting"
        // means this push was coalesced into already-pending work (its payload dropped) —
        // previously invisible in the log (#988).
        Logger.i(tag = "PushCapture") {
            "enqueue: uniqueWork=$WORK_TAG policy=KEEP expedited=downgradable-to-regular"
        }
    }

    /** FCM priority ints per RemoteMessage: 1=high, 2=normal, 0=unknown. */
    private fun pri(p: Int): String = when (p) {
        RemoteMessage.PRIORITY_HIGH -> "high"
        RemoteMessage.PRIORITY_NORMAL -> "normal"
        else -> "unknown($p)"
    }

    companion object {
        const val WORK_TAG = "drive_fcm_sync"
        const val KEY_TITLE = "fcm_title"
        const val KEY_BODY = "fcm_body"
        const val KEY_DATA_PREFIX = "fcm_data_"
        const val KEY_ENQUEUED_AT_MS = "fcm_enqueued_at_ms"
    }
}
