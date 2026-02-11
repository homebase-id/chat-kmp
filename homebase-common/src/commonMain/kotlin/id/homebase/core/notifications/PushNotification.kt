package id.homebase.core.notifications

import kotlinx.serialization.Serializable

/** Push notification payload matching the backend DevicePushNotificationRequest format. */
@Serializable
data class PushNotification(
    val id: String,
    val senderId: String,
    val unread: Boolean,
    val created: Long,
    val options: PushNotificationPayloadOptions,
    val appDisplayName: String? = null
)

/** Options embedded in a push notification payload. */
@Serializable
data class PushNotificationPayloadOptions(
    val appId: String,
    val typeId: String,
    val tagId: String = "",
    val silent: Boolean = false,
    val unEncryptedMessage: String? = null,
    val peerSubscriptionId: String? = null,
)

/** Wrapper for push notification messages as delivered by the backend. */
@Serializable
data class PushNotificationMessage(
    val correlationId: String,
    val id: String,
    val data: PushNotification,
    val timestamp: String,
    val version: Int
)
