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
import id.homebase.api.diagnostics.BgTrace
import id.homebase.core.location.PushLocationCapture
import id.homebase.core.notifications.NotificationService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.android.inject

class DriveFcmService : FirebaseMessagingService() {

    private val notificationService: NotificationService by inject()
    private val pushLocationCapture: PushLocationCapture by inject()

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
        // #1109 background wake-cause attribution (an FCM push woke the process).
        BgTrace.log(BgTrace.wake("fcm", "priority=${pri(message.priority)}"))

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

        // #987: the expedited work request above silently downgrades to DEFERRABLE when the
        // Doze quota is exhausted — the worker (and its capture) can then run hours late.
        // onMessageReceived itself executes inside the high-priority FCM wake (~10s budget,
        // network up), so run the bounded capture + forced outbox drain inline, AFTER the
        // worker enqueue so chat sync/notification display is never delayed. The worker's
        // own captureAndUpload call later is a cheap coalesce (60s acquisition guard + 60s
        // flush rate-gate + replace-enqueue) and the free retry leg if this inline drain
        // failed. runBlocking is the accepted bridge here — FirebaseMessagingService has no
        // goAsync, and returning early would drop the process's wake guarantee; a queued
        // next FCM message waits ≤8s on this thread, which back-to-back pushes absorb via
        // the last-known/nothing-pending fast paths.
        Logger.i(tag = "PushCapture") { "inline-capture: starting budgetMs=$INLINE_CAPTURE_BUDGET_MS" }
        runBlocking {
            withTimeoutOrNull(INLINE_CAPTURE_BUDGET_MS + BUDGET_SLACK_MS) {
                runCatching { pushLocationCapture.captureAndUpload(INLINE_CAPTURE_BUDGET_MS) }
                    .onFailure { Logger.w(tag = "PushCapture", throwable = it) { "inline-capture: failed" } }
            } ?: Logger.w(tag = "PushCapture") { "inline-capture: hard timeout at ${INLINE_CAPTURE_BUDGET_MS + BUDGET_SLACK_MS}ms" }
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

        /** High-priority FCM grants ~10s of execution: 5s GPS cap + ~3s drain POST inside
         *  an 8s budget, 2s process slack (#987). */
        const val INLINE_CAPTURE_BUDGET_MS = 8_000L

        /** Outer hard-stop margin over the cooperative budget (belt and braces). */
        const val BUDGET_SLACK_MS = 1_000L
    }
}
