package id.homebase.upload

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.AppNotificationOptions
import id.homebase.api.client.drives.upload.PayloadDeleteKey
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.common.OdinId
import id.homebase.api.crypto.ByteArrayUtil
import kotlin.uuid.Uuid

/**
 * Everything [UploadService.updateFile] needs to update an existing file (an
 * `UpdateFileByUniqueIdRequest`). Covers header-only updates (no new payloads), new-payload
 * updates (a plaintext [bundle] the service encrypts), delete-only manifests ([toDeletePayloads]),
 * and re-attaching a file's existing already-encrypted payloads ([preEncryptedPayloads], e.g.
 * add-recipients) — for which the service skips encryption.
 *
 * [keyHeader] must reuse the existing file's AES key with a fresh IV — the server rejects an
 * update whose AES key differs ("AES key must match"). [metadata] is unencrypted and carries the
 * `versionTag`; the service encrypts a copy for the request and passes the plain one to the
 * optimistic `writeUpdate`.
 */
data class MediaUpdateSpec(
    val driveId: Uuid,
    val uniqueId: Uuid,
    val keyHeader: KeyHeader,
    val metadata: UploadFileMetadata,
    /** Plaintext NEW payloads to append/overwrite; the service encrypts them. Null = header-only. */
    val bundle: PayloadBundle? = null,
    /**
     * The existing file's id. Pass it ONLY when [bundle] is the file's **complete** new payload set
     * (a full-state overwrite — e.g. a vault note edit whose single payload replaces the note body),
     * NOT an incremental append/replace-one-page. When set, [UploadService.updateFile] writes the
     * new payload descriptors THROUGH to the local store and seeds the freshly-encrypted bytes under
     * this fileId ([seedCache]) — so a read-back *before* the outbox send lands returns the new body
     * instead of decrypting the stale server bytes with the new IV (issue #927). Leaving it null
     * keeps the descriptor-preserving optimistic write (header-only edits, appends, add-recipients).
     */
    val fileId: Uuid? = null,
    /** Seed the freshly-encrypted payload bytes under [fileId] so a pre-send read-back is correct. */
    val seedCache: Boolean = true,
    /**
     * Already-encrypted payloads to re-attach as-is (the service skips encryption). Mutually
     * exclusive with [bundle]; used when reusing a file's existing payloads (add-recipients).
     */
    val preEncryptedPayloads: List<PayloadFile>? = null,
    val preEncryptedThumbnails: List<ThumbnailFile>? = null,
    /** Transit recipients for the update (empty = local-only). Lives in the instruction set. */
    val recipients: List<OdinId> = emptyList(),
    val locale: UpdateLocale = UpdateLocale.Local,
    val transferIv: ByteArray = ByteArrayUtil.getRndByteArray(16),
    val useAppNotification: Boolean = false,
    val appNotificationOptions: AppNotificationOptions? = null,
    /** Payload keys to delete (for delete-only / mixed manifests). */
    val toDeletePayloads: List<PayloadDeleteKey>? = null,
    val generatePayloadIv: Boolean = false,
    /** true → `replaceEnqueue` (supersede a pending row for this file); false → `tryEnqueue`. */
    val replace: Boolean = true,
    val dependencyUniqueId: Uuid? = null,
    val priority: Long = 1,
    val sendNow: Boolean = true,
    val writeOptimistic: Boolean = true,
)
