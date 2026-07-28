package id.homebase.core.notifications

import co.touchlab.kermit.Logger
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.location.DEFAULT_PUSH_CAPTURE_BUDGET_MS
import id.homebase.core.location.PushLocationCapture
import id.homebase.core.sync.BackgroundSyncOrchestrator
import id.homebase.core.sync.SyncOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatformTools

/**
 * Single shared entry point that every platform glue funnels into for the two
 * notification lifecycle events: silent-push *arrival* and user *tap*. Each
 * platform calls exactly one method per event with the same payload shape,
 * which means adding a fourth platform (or fixing a bug across all three) is
 * one place.
 *
 * This class is composition over [NotificationService] +
 * [BackgroundSyncOrchestrator], not a replacement. The shared work — building
 * the system notification, setting [PendingNotificationTap], running sync —
 * still lives where it did. What this consolidates is the *order* and the
 * *threading model* of those calls, so platform code no longer has to remember
 * "show then sync" vs "sync then show" or "use WorkManager vs direct call."
 *
 * On Android, FCM arrival enqueues a `DriveSyncWorker` whose body invokes
 * [onPushArrived]. On iOS, `didReceiveRemoteNotification(... fetchCompletionHandler:)`
 * invokes [onPushArrivedAsync] inside the 30 s background-fetch window. On
 * tap (Android `MainActivity`, iOS `UNUserNotificationCenterDelegate.didReceive`,
 * Desktop `NotificationClickRouter`), each glue invokes [onNotificationTappedAsync].
 *
 * The Swift-friendly `*Async` callback variants exist because Kotlin suspend
 * is not directly callable from Swift; they mirror the pattern already used
 * by [BackgroundSyncOrchestrator.triggerSync].
 */
class NotificationEntry(
    private val notificationService: NotificationService,
    private val orchestrator: BackgroundSyncOrchestrator,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
    private val pushLocationCapture: PushLocationCapture,
) {

    /**
     * Called when the OS delivers a silent push / FCM data message in the
     * background. Builds/refreshes the system notification and triggers a
     * background sync. The sync skips itself when WS is online (see
     * [BackgroundSyncOrchestrator.syncIfAuthenticated]).
     */
    suspend fun onPushArrived(
        title: String?,
        body: String?,
        data: Map<String, String>,
    ): SyncOutcome {
        Logger.i(tag = "NotificationEntry") {
            "onPushArrived title=$title body=$body data.size=${data.size}"
        }
        notificationService.onFcmMessageReceived(title, body, data)
        val outcome = orchestrator.syncIfAuthenticated()
        // A push briefly wakes the process — an opportunistic free moment to record a fresh point if
        // tracking is on and the last one is stale (#878), AND to make the resulting hour-file
        // upload actually land before the wake ends (#987: the websocket never connects in a
        // background wake, so without the bounded forced drain inside captureAndUpload the
        // enqueued row would sit until the next foreground connect). Awaited (bounded by the
        // budget) so iOS's completionHandler fires after the upload attempt, not before.
        // Best-effort — never fail the push sync over it. The capture result + drain outcome
        // are logged under the PushCapture tag (#988).
        runCatching { pushLocationCapture.captureAndUpload(DEFAULT_PUSH_CAPTURE_BUDGET_MS) }
            .onFailure { Logger.w(tag = "NotificationEntry", throwable = it) { "push capture+upload failed" } }
        return outcome
    }

    /**
     * Called when the user taps a delivered notification, on any platform,
     * whether the app was killed, backgrounded, or foregrounded. Routes to
     * [NotificationService.handleNotificationClicked] which sets
     * [PendingNotificationTap] (resolved by `ConversationListViewModel` for
     * navigation). Defensively triggers a sync if WS is offline so the
     * navigation lands on a fresh DB row rather than a stale placeholder.
     */
    suspend fun onNotificationTapped(payload: PayloadData) {
        Logger.i(tag = "NotificationEntry") {
            "onNotificationTapped wsOnline=${authConnectionCoordinator.isOnline.value}"
        }
        notificationService.handleNotificationClicked(payload)
        if (!authConnectionCoordinator.isOnline.value) {
            orchestrator.syncIfAuthenticated()
        }
    }

    /** Swift-callable bridge for [onPushArrived]. */
    fun onPushArrivedAsync(
        title: String?,
        body: String?,
        data: Map<String, String>,
        onComplete: (success: Boolean) -> Unit,
    ) {
        CoroutineScope(Dispatchers.Default).launch {
            Logger.i(tag = "PushCapture") { "handler: onPushArrivedAsync entered dataKeys=${data.size}" }
            val outcome = runCatching { onPushArrived(title, body, data) }
                .getOrElse { SyncOutcome.Failed(it) }
            // Ordering of this line vs LocationTrackUploader's "Hour-file upload confirmed"
            // answers "did the upload land before iOS suspended us" (#988): a confirm line
            // AFTER this one means the outbox was still draining past the fetch window.
            Logger.i(tag = "PushCapture") {
                "handler: complete success=${outcome is SyncOutcome.Success} outcome=${outcome::class.simpleName}"
            }
            onComplete(outcome is SyncOutcome.Success)
        }
    }

    /** Swift-callable bridge for [onNotificationTapped]. */
    fun onNotificationTappedAsync(
        payload: PayloadData,
        onComplete: () -> Unit = {},
    ) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                onNotificationTapped(payload)
            } catch (e: Throwable) {
                Logger.e(tag = "NotificationEntry") {
                    "onNotificationTapped failed: ${e.message}"
                }
            } finally {
                onComplete()
            }
        }
    }

    companion object {
        fun fromKoin(): NotificationEntry =
            KoinPlatformTools.defaultContext().get().get()
    }
}
