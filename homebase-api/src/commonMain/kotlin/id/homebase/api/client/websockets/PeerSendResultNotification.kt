package id.homebase.api.client.websockets

import id.homebase.api.client.drives.files.TransferStatus
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * The `data` of a [ClientNotificationType.peerSendResult]: how a send from the transient drive went at [recipient], heard
 * only by the app that sent it and never kept for one not connected. It may come again; the latest counts.
 */
@Serializable
data class PeerSendResultNotification(
    val recipient: OdinId,
    @Serializable(with = UuidSerializer::class) val driveId: Uuid,
    @Serializable(with = UuidSerializer::class) val globalTransitId: Uuid,
    /** The `newVersionTag` the upload or update gave back: which send of the file this is. */
    @Serializable(with = UuidSerializer::class) val sentVersionTag: Uuid,
    val isUpdate: Boolean,
    val status: TransferStatus,
    val willRetry: Boolean,
    /** On delivery, the recipient's copy's version tag, for the next edit to name. */
    @Serializable(with = UuidSerializer::class) val versionTag: Uuid? = null,
)
