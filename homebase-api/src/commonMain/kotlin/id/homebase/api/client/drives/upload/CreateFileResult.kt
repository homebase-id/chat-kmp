package id.homebase.api.client.drives.upload

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Per-payload receipt in an upload/update response. The server reads [uid] and
 * [lastModified] from the same descriptor objects the file header stores, so
 * [lastModified] is byte-identical to what later appears in the synced-down
 * header at `fileMetadata.payloads[].lastModified` — directly usable at upload
 * time as the version segment of the thumbnail cache key (and the
 * `?lastModified=` thumb GET param), without waiting for sync-back.
 *
 * Create responses carry one entry per manifest payload (nothing filtered);
 * update responses carry entries only for payloads uploaded in that request
 * (AppendOrOverwrite) — untouched payloads keep their old values.
 */
@Serializable
data class PayloadUploadReceipt(
    val key: String,
    /** Server-assigned UnixTimeUtcUnique for the payload. */
    val uid: Long,
    /** Milliseconds since epoch; the thumb version identifier. */
    val lastModified: Long,
)

@Serializable
data class CreateFileResult(
    val fileId: Uuid,
    val driveId: Uuid,
    var globalTransitId: Uuid? = null,
    val recipientStatus: Map<String, TransferUploadStatus>? = null,
    val newVersionTag: Uuid,
    /** Empty on servers that pre-date the receipt field (and for metadata-only requests). */
    val payloads: List<PayloadUploadReceipt> = emptyList(),
)


@Serializable
data class UpdateFileResult(
    val fileId: Uuid,
    val driveId: Uuid,
    val globalTransitId: Uuid? = null,
    val recipientStatus: Map<String, TransferUploadStatus>? = null,
    val newVersionTag: Uuid,
    /** Empty on servers that pre-date the receipt field (and for metadata-only requests). */
    val payloads: List<PayloadUploadReceipt> = emptyList(),
)
