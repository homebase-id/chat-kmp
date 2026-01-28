package id.homebase.api.client.drives.upload

import kotlinx.serialization.Serializable

/**
 * Push notification options for transit operations.
 */
@Serializable
data class PushNotificationOptions(
    val appId: String,
    val typeId: String,
    val tagId: String,
    val silent: Boolean,
    val unEncryptedMessage: String? = null,
    val peerSubscriptionId: String? = null,
    val recipients: List<String>? = null
)
