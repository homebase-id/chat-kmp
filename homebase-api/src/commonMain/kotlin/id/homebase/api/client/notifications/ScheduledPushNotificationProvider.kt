package id.homebase.api.client.notifications

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Options for the notification to send. Mirrors [id.homebase.api.client.drives.upload.PushNotificationOptions]
 * (the shape the existing immediate-send chat path uses) but with [Uuid]-typed ids instead of
 * strings. [peerSubscriptionId] is optional here — unlike
 * [id.homebase.api.client.drives.upload.AppNotificationOptions], whose non-null requirement only
 * applies to the existing-file-update path — to match what callers of the real immediate-send path
 * actually send today.
 */
@Serializable
data class ScheduledPushNotificationOptions(
    val appId: Uuid,
    val typeId: Uuid,
    val tagId: Uuid,
    val silent: Boolean,
    val peerSubscriptionId: Uuid? = null,
    val recipients: List<OdinId>? = null,
    val unEncryptedMessage: String? = null
)

@Serializable
data class SchedulePushNotificationRequest(
    val options: ScheduledPushNotificationOptions,
    val sendAt: UnixTimeUtc,
    val recurrenceInterval: Long? = null
)

@Serializable
data class SchedulePushNotificationResponse(val jobId: Uuid)

enum class ScheduledPushNotificationState {
    Scheduled, Preflight, Running, Succeeded, Failed
}

@Serializable
data class ScheduledPushNotificationEntry(
    val jobId: Uuid,
    val options: ScheduledPushNotificationOptions,
    val sendAt: UnixTimeUtc,
    val state: ScheduledPushNotificationState,
    val attemptCount: Int,
    val maxAttempts: Int,
    val recurrenceInterval: Long? = null
)

/**
 * Client for the `/api/v2/notify/push/schedule` endpoints — schedule/update/cancel/list a
 * future (optionally recurring) push notification. Requires the `SendPushNotifications`
 * permission, same as the immediate-send path; the server enforces this, not the client.
 *
 * [update]/[cancel] both surface a 404 [id.homebase.api.client.NotFoundException] when the
 * job doesn't exist, already fired (one-shot only), or belongs to a different app — the API
 * doesn't distinguish these, so neither does this client.
 */
class ScheduledPushNotificationProvider(httpClient: HttpClient, credentialsManager: CredentialsManager) :
    OdinApiProviderBase(httpClient, credentialsManager) {

    suspend fun schedule(request: SchedulePushNotificationRequest): Uuid {
        validateRecurrenceInterval(request.recurrenceInterval)
        val creds = requireCreds()
        val response = encryptedPostJson(
            url = apiUrl(creds.domain, "/notify/push/schedule"),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(request),
            secret = creds.secret
        )
        throwForFailure(response)
        return deserialize<SchedulePushNotificationResponse>(response.body).jobId
    }

    suspend fun update(jobId: Uuid, request: SchedulePushNotificationRequest) {
        validateRecurrenceInterval(request.recurrenceInterval)
        val creds = requireCreds()
        val response = encryptedPutJson(
            url = apiUrl(creds.domain, "/notify/push/schedule/$jobId"),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(request),
            secret = creds.secret
        )
        throwForFailure(response)
    }

    suspend fun cancel(jobId: Uuid) {
        val creds = requireCreds()
        val response = encryptedDelete(
            url = apiUrl(creds.domain, "/notify/push/schedule/$jobId"),
            token = creds.accessToken,
            secret = creds.secret
        )
        throwForFailure(response)
    }

    suspend fun list(): List<ScheduledPushNotificationEntry> {
        val creds = requireCreds()
        val response = encryptedGet(
            url = apiUrl(creds.domain, "/notify/push/schedule"),
            token = creds.accessToken,
            secret = creds.secret
        )
        throwForFailure(response)
        return deserialize(response.body)
    }

    companion object {
        /** Server-enforced floor for [SchedulePushNotificationRequest.recurrenceInterval]. */
        const val MIN_RECURRENCE_INTERVAL_MS = 300_000L

        /**
         * Fails fast on a sub-floor recurrence interval instead of round-tripping to the server
         * for what will always be a 400 ArgumentError. Public so callers building a schedule form
         * can reuse the exact same floor for inline validation.
         */
        fun validateRecurrenceInterval(recurrenceIntervalMs: Long?) {
            require(recurrenceIntervalMs == null || recurrenceIntervalMs >= MIN_RECURRENCE_INTERVAL_MS) {
                "recurrenceInterval must be null (one-shot) or >= ${MIN_RECURRENCE_INTERVAL_MS}ms (5 minutes)"
            }
        }
    }
}
