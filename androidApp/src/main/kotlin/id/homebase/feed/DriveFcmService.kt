package id.homebase.feed

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.mmk.kmpnotifier.notification.NotifierManager
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
        // Schedule background sync
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
                    .build()
            )

        // Forward to NotificationService for notification display
        notificationService.onFcmMessageReceived(
            title = message.notification?.title,
            body = message.notification?.body,
            data = message.data,
        )
    }

    companion object {
        const val WORK_TAG = "drive_fcm_sync"
    }
}
