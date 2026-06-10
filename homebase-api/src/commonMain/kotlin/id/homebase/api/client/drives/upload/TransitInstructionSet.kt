package id.homebase.api.client.drives.upload

import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.common.OdinId
import id.homebase.api.prototype.lib.serialization.Base64ByteArraySerializer
import id.homebase.api.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Instruction set for an **over-peer** file send: a non-owner writing a file straight onto another
 * identity's drive (a community owner's collaborative drive). Mirrors the JS `TransitInstructionSet`
 * (`js-lib/.../peer/peerData/PeerTypes.ts`). Unlike [UploadInstructionSet] there is no local
 * `storageOptions`/`driveId` — the target is the remote [remoteTargetDrive] on the [recipients]'
 * host(s); the user's own host brokers the send (transit/outbox).
 */
@Serializable
data class TransitInstructionSet(
    @Serializable(with = Base64ByteArraySerializer::class) val transferIv: ByteArray,
    val manifest: UploadManifest,
    /** Owner-hosted drive the file lands on (the community drive). */
    val remoteTargetDrive: TargetDrive,
    /** Identities to deliver to — for a community write this is the single owner. */
    val recipients: List<OdinId>,
    /** When set, overwrites the existing remote file with this globalTransitId (edit path). */
    @Serializable(with = UuidSerializer::class) val overwriteGlobalTransitFileId: Uuid? = null,
    val schedule: ScheduleOptions? = null,
    val priority: PriorityOptions? = null,
    /** `"metadataOnly"` to send only the header (no payloads). */
    val storageIntent: String? = null,
    val notificationOptions: PushNotificationOptions? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as TransitInstructionSet
        if (!transferIv.contentEquals(other.transferIv)) return false
        if (manifest != other.manifest) return false
        if (remoteTargetDrive != other.remoteTargetDrive) return false
        if (recipients != other.recipients) return false
        if (overwriteGlobalTransitFileId != other.overwriteGlobalTransitFileId) return false
        if (schedule != other.schedule) return false
        if (priority != other.priority) return false
        if (storageIntent != other.storageIntent) return false
        if (notificationOptions != other.notificationOptions) return false
        return true
    }

    override fun hashCode(): Int {
        var result = transferIv.contentHashCode()
        result = 31 * result + manifest.hashCode()
        result = 31 * result + remoteTargetDrive.hashCode()
        result = 31 * result + recipients.hashCode()
        result = 31 * result + (overwriteGlobalTransitFileId?.hashCode() ?: 0)
        result = 31 * result + (schedule?.hashCode() ?: 0)
        result = 31 * result + (priority?.hashCode() ?: 0)
        result = 31 * result + (storageIntent?.hashCode() ?: 0)
        result = 31 * result + (notificationOptions?.hashCode() ?: 0)
        return result
    }
}

/** Server response to an over-peer send. Mirrors the JS `TransitUploadResult`. */
@Serializable
data class TransitUploadResult(
    val recipientStatus: Map<String, TransferUploadStatus> = emptyMap(),
    val remoteGlobalTransitIdFileIdentifier: RemoteGlobalTransitIdFileIdentifier? = null,
)

@Serializable
data class RemoteGlobalTransitIdFileIdentifier(
    @Serializable(with = UuidSerializer::class) val globalTransitId: Uuid,
    val targetDrive: TargetDrive,
)
