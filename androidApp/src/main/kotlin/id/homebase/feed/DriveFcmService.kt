package id.homebase.feed

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
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
        // Hand the FCM payload to the shared NotificationEntry via a worker —
        // the worker runs the same onPushArrived(...) body iOS calls inline,
        // and WorkManager gives us OS-budgeted background guarantees that
        // FCM's onMessageReceived callback by itself does not.
        val inputData = Data.Builder().apply {
            putString(KEY_TITLE, message.notification?.title)
            putString(KEY_BODY, message.notification?.body)
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
    }

    companion object {
        const val WORK_TAG = "drive_fcm_sync"
        const val KEY_TITLE = "fcm_title"
        const val KEY_BODY = "fcm_body"
        const val KEY_DATA_PREFIX = "fcm_data_"
    }
}
