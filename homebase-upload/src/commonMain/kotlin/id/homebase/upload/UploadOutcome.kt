package id.homebase.upload

import id.homebase.api.client.drives.files.PayloadDescriptor
import kotlin.uuid.Uuid

/**
 * Outcome of [UploadService.upload]. Distinguishes the cases a feature must handle
 * differently — durable success, a swept pre-encryption source (re-pick), a benign
 * already-queued collision, the strand guard, and a real failure.
 */
sealed interface UploadOutcome {
    /**
     * The upload is durably queued. [optimisticFileId] is the seeded/optimistic id when one was
     * written. [payloadDescriptors] are the descriptors UploadService built from the encrypted
     * bundle (the optimistic mirror of what the server will return) — returned so a feature can
     * reuse them for its own optimistic bookkeeping (e.g. the sticker tray row) without
     * re-deriving from an encrypted result it no longer holds. Null for a header-only upload.
     */
    data class Enqueued(
        val uniqueId: Uuid,
        val optimisticFileId: Uuid?,
        val payloadDescriptors: List<PayloadDescriptor>? = null,
    ) : UploadOutcome

    /**
     * Deliverable A (#844): a raw pre-encryption source was gone at encrypt time — swept,
     * OS-evicted, or a `content://`/`ph://` grant revoked. NOTHING was enqueued; the caller
     * should prompt a re-pick rather than retry.
     */
    data class SourceMissing(val missingPaths: List<String>) : UploadOutcome

    /** replaceEnqueue refused to downgrade a still-pending create. Re-enqueue as a create. */
    data object WouldStrandCreate : UploadOutcome

    /** A row for this (driveId, uniqueId) is already pending — usually benign. */
    data class AlreadyQueued(val uniqueId: Uuid) : UploadOutcome

    /** Enqueue failed for a non-constraint reason. NOTHING was enqueued. */
    data class Failed(val cause: Throwable) : UploadOutcome
}
