package id.homebase.api.client.drives.upload

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import id.homebase.api.common.OdinId


/**
 * Options for notifying a recipient identity server
 */
@Serializable
data class AppNotificationOptions(
    val appId: Uuid,
    val typeId: Uuid,

    /** An app-specific identifier */
    val tagId: Uuid,

    /** Do not play a sound or vibrate the phone */
    val silent: Boolean,

    /**
     * An app-specified field used to filter what notifications are allowed
     * to be received from a peer identity
     */
    val peerSubscriptionId: Uuid,

    /**
     * If specified, the push notification should only be sent to this list
     * of recipients (instead of any other list)
     */
    val recipients: List<OdinId>? = null,

    val unEncryptedMessage: String? = null
)