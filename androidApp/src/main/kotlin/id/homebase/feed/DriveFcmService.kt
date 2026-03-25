package id.homebase.feed

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.google.firebase.messaging.RemoteMessage
import com.mmk.kmpnotifier.firebase.KMPNotifierFirebaseMessagingService

class DriveFcmService : KMPNotifierFirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
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
    }

    companion object {
        const val WORK_TAG = "drive_fcm_sync"
    }
}
