package id.homebase.core.notifications

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.mapToMessageData
import id.homebase.core.config.AppConfig
import id.homebase.core.config.chatTargetDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val BODY_PREVIEW_MAX_CODEPOINTS = 200

/**
 * JVM-only bridge that turns inbound chat messages (delivered via the websocket /
 * sync event bus) into local OS notifications. Android and iOS receive push
 * notifications from FCM/APNs and go through [NotificationService.onFcmMessageReceived]
 * — desktop has no push channel, so this bridge fabricates the same payload shape
 * from live messages so the rest of the notification pipeline (foreground suppression,
 * in-app banner, per-conversation count summary, chime cooldown, click navigation)
 * is shared across all platforms.
 */
class DesktopChatNotificationBridge(
    private val eventBus: EventBus,
    private val notificationService: NotificationService,
    private val credentialsManager: CredentialsManager,
    private val scope: CoroutineScope,
) {
    private val chatAppIdDashed: String = Uuid.parse(AppConfig.APP_ID).toString()
    private val chatDriveId: Uuid = chatTargetDrive.alias

    @Volatile
    private var started: Boolean = false

    /** Messages with `created` before this instant are treated as replay and skipped. */
    @Volatile
    private var freshnessFloor: Instant = Instant.DISTANT_PAST

    fun start() {
        if (started) return
        started = true
        freshnessFloor = Clock.System.now()
        Logger.i(tag = "DesktopChatNotificationBridge") {
            "Started (freshnessFloor=$freshnessFloor)"
        }
        scope.launch {
            eventBus.events
                .filterIsInstance<BackendEvent.DataEvent.BatchReceived>()
                .filter { it.driveId == chatDriveId }
                .collect { event -> handleBatch(event) }
        }
    }

    private suspend fun handleBatch(event: BackendEvent.DataEvent.BatchReceived) {
        val domain = try {
            credentialsManager.requireActiveDomain()
        } catch (e: Exception) {
            Logger.d(tag = "DesktopChatNotificationBridge") {
                "No active domain, skipping batch: ${e.message}"
            }
            return
        }

        val floor = freshnessFloor
        for (file in event.batchData) {
            if (file.fileMetadata.appData.fileType != ChatProtocol.MessageFileType) continue
            val message = try {
                mapToMessageData(file, credentialsManager)
            } catch (e: Exception) {
                Logger.w(tag = "DesktopChatNotificationBridge") {
                    "Failed to map message: ${e.message}"
                }
                null
            } ?: continue

            if (message.isDeleted || message.isStatusMessage || message.isPendingSend) continue
            if (message.isAuthoredBy(domain)) continue
            if (message.created < floor) continue

            val senderId = message.originalAuthor?.domainName ?: continue
            val payload = buildSyntheticPayload(
                senderId = senderId,
                conversationId = message.conversationId,
                createdEpochMs = message.created.toEpochMilliseconds(),
                body = message.content,
            )
            val json = try {
                OdinSystemSerializer.serialize(payload)
            } catch (e: Exception) {
                Logger.w(tag = "DesktopChatNotificationBridge") {
                    "Serialization failed: ${e.message}"
                }
                continue
            }
            notificationService.onFcmMessageReceived(
                title = null,
                body = null,
                data = mapOf("data" to json),
            )
        }
    }

    private fun buildSyntheticPayload(
        senderId: String,
        conversationId: Uuid,
        createdEpochMs: Long,
        body: String,
    ): PushNotification {
        val preview = body.truncateToCodePoints(BODY_PREVIEW_MAX_CODEPOINTS)
        return PushNotification(
            senderId = senderId,
            created = createdEpochMs,
            options = PushNotificationPayloadOptions(
                appId = chatAppIdDashed,
                typeId = conversationId.toString(),
                unEncryptedMessage = preview,
                silent = false,
            ),
            appDisplayName = AppConfig.APP_NAME,
        )
    }
}
