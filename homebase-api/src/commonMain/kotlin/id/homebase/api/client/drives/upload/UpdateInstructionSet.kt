package id.homebase.api.client.drives.upload

import id.homebase.api.client.drives.TargetDrive
import id.homebase.homebasekmppoc.prototype.lib.serialization.Base64ByteArraySerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** File identifier using fileId for local operations. */
@Serializable
data class FileIdFileIdentifier(
    val fileId: String,
    val targetDrive: TargetDrive
)

/** Represents the locale of an update operation. */
enum class UpdateLocale {
    Peer,
    Local
}

@Serializable
data class FileUpdateInstructionSet(
    @Serializable(with = Base64ByteArraySerializer::class)
    val transferIv: ByteArray,

    val locale: UpdateLocale,

    val recipients: List<String>,

    val manifest: UpdateManifest,

    val useAppNotification: Boolean = false,

    val appNotificationOptions: AppNotificationOptions? = null
)


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
    val recipients: List<String>? = null,

    val unEncryptedMessage: String? = null
)