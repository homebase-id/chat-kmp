package id.homebase.upload

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadFileMetadata
import kotlin.uuid.Uuid

/**
 * Everything [UploadService] needs to encrypt, enqueue, seed and optimistically write a
 * new-file upload. Feature-specific preparation (source resolve, HEIC convert, recipient
 * lookup, push-notification options, dependency chaining) stays in the feature and is passed
 * in here already resolved — the spec is a plain hand-off, not a place for feature logic.
 *
 * Note: [metadata] is the UNENCRYPTED metadata. [UploadService] applies `encryptContent` for
 * the upload request and passes the unencrypted copy to the optimistic write. Build its
 * `previewThumbnail` from the plaintext [bundle]'s `previewThumbs` (encryption passes those
 * through unchanged), so the spec has no dependency on the encrypted result.
 */
data class MediaUploadSpec(
    val driveId: Uuid,
    val uniqueId: Uuid,
    val keyHeader: KeyHeader,
    /** Plaintext payloads; null for a header-only upload. */
    val bundle: PayloadBundle?,
    /** Unencrypted metadata; UploadService encrypts a copy for the request. */
    val metadata: UploadFileMetadata,
    val transit: TransitOptions? = null,
    val dependencyUniqueId: Uuid? = null,
    val priority: Long = 1,
    val sendNow: Boolean = true,
    /**
     * true → `replaceEnqueue` (supersede a pending row, e.g. an hourly location flush or an
     * edit coalescing into a still-queued create); false → `tryEnqueue`.
     */
    val replace: Boolean = false,
    /** Recipient count recorded on the optimistic row (delivery-status display). */
    val originalRecipientCount: Int = 0,
    val fileSystemType: FileSystemType = FileSystemType.Standard,
    /** Pre-minted so the caller (and the cache seed) can reference the optimistic fileId. */
    val optimisticFileId: Uuid = Uuid.random(),
    val writeOptimistic: Boolean = true,
    val seedCache: Boolean = true,
)
