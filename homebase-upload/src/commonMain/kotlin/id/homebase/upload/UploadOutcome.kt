package id.homebase.upload

import kotlin.uuid.Uuid

/**
 * Outcome of [UploadService.upload]. Distinguishes the cases a feature must handle
 * differently — durable success, a swept pre-encryption source (re-pick), a benign
 * already-queued collision, the strand guard, and a real failure.
 */
sealed interface UploadOutcome {
    /** The upload is durably queued. [optimisticFileId] is the seeded/optimistic id when one was written. */
    data class Enqueued(val uniqueId: Uuid, val optimisticFileId: Uuid?) : UploadOutcome

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
