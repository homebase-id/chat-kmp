package id.homebase.photobase.backup

import co.touchlab.kermit.Logger
import id.homebase.api.client.notifications.CancelScheduledPushRequest
import id.homebase.api.client.notifications.SchedulePushNotificationRequest
import id.homebase.api.client.notifications.ScheduledPushNotificationEntry
import id.homebase.api.client.notifications.ScheduledPushNotificationOptions
import id.homebase.api.client.notifications.ScheduledPushNotificationProvider
import id.homebase.api.client.notifications.ScheduledPushNotificationState
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.sync.database.OutboxSync
import id.homebase.core.config.AppConfig
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid

/**
 * The identity's one recurring silent push, which wakes all its phones. Devices find it by tag, so
 * the first one to enable backup creates it and the others leave it alone, unless its interval
 * differs from theirs. Always on the chat appId, the one pushes are accepted under.
 */
class BackupPushSchedule(
    private val provider: ScheduledPushNotificationProvider,
    private val outboxSync: OutboxSync,
    private val nowMs: () -> Long,
) {
    /** Creates or corrects the schedule if the server's isn't what [settings] asks for. Costs one list() when it's fine. */
    suspend fun ensure(settings: BackupSettings) {
        if (!settings.enabled) return
        val existing = listOrNull() ?: return
        if (needsScheduling(existing, settings)) schedule(settings)
    }

    /** For a change made where backup may be off, like desktop: never creates a schedule no phone asked for. */
    suspend fun correctIfScheduled(settings: BackupSettings) {
        val existing = listOrNull() ?: return
        if (existing.any { it.isOurs() } && needsScheduling(existing, settings)) schedule(settings)
    }

    // Null only loses a hint: a foreground run checks again.
    private suspend fun listOrNull(): List<ScheduledPushNotificationEntry>? = try {
        provider.list()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "could not list scheduled pushes" }
        null
    }

    suspend fun schedule(settings: BackupSettings) {
        val result = outboxSync.enqueueScheduledPush(OUTBOX_ROW_DRIVE, PhotobaseProtocol.BackupPushTagId, requestFor(settings))
        Logger.i(tag = TAG) { "schedule interval=${settings.pushIntervalMs}ms enqueue=$result" }
    }

    suspend fun cancel() {
        val result = outboxSync.enqueueCancelScheduledPush(
            OUTBOX_ROW_DRIVE,
            PhotobaseProtocol.BackupPushTagId,
            CancelScheduledPushRequest(PhotobaseProtocol.BackupPushTagId),
        )
        Logger.i(tag = TAG) { "cancel enqueue=$result" }
    }

    /** Direct, not through the Outbox: logout wipes the Outbox and the credentials a queued cancel would need. */
    suspend fun cancelBeforeLogout() {
        try {
            withTimeoutOrNull(LOGOUT_CANCEL_TIMEOUT) {
                provider.list().filter { it.isOurs() }.forEach { provider.cancel(it.jobId) }
            } ?: Logger.w(tag = TAG) { "cancel before logout timed out" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "cancel before logout failed" }
        }
    }

    internal fun requestFor(settings: BackupSettings) = SchedulePushNotificationRequest(
        options = ScheduledPushNotificationOptions(
            appId = Uuid.parse(AppConfig.APP_ID),
            typeId = PhotobaseProtocol.BackupPushTypeId,
            tagId = PhotobaseProtocol.BackupPushTagId,
            silent = true,
        ),
        sendAt = UnixTimeUtc(nowMs() + settings.pushIntervalMs),
        recurrenceInterval = settings.pushIntervalMs,
    )

    internal fun needsScheduling(jobs: List<ScheduledPushNotificationEntry>, settings: BackupSettings): Boolean {
        val ours = jobs.filter { it.isOurs() && it.state != ScheduledPushNotificationState.Failed }
        return ours.size != 1 || ours.single().recurrenceInterval != settings.pushIntervalMs
    }

    private fun ScheduledPushNotificationEntry.isOurs() = options.tagId == PhotobaseProtocol.BackupPushTagId

    private companion object {
        const val TAG = "BackupPushSchedule"
        val LOGOUT_CANCEL_TIMEOUT = 5.seconds

        // Outbox rows need a (driveId, uniqueId); push rows use a pseudo-drive, like event reminders.
        val OUTBOX_ROW_DRIVE: Uuid = Uuid.parse("0fb9bb5e-6d40-4f1a-9a57-0a8a2c7d3c11")
    }
}
