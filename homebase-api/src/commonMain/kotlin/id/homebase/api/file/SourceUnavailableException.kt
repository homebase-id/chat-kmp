package id.homebase.api.file

/**
 * A raw pre-encryption upload source was gone when the encryption-on-send step
 * tried to read it — swept by the `CacheSweeper`, evicted by the OS, or a
 * `content://`/`ph://` permission revoked. These temps are deliberately
 * disposable (durability begins at encryption-on-send, not before), so a
 * missing one is not an error to retry: the right outcome is "re-pick".
 *
 * Thrown from [FileOperationsProvider.sourceExists]-guarded read sites (see
 * `PayloadBundleEncryptionService`). Because encryption runs *before* the outbox
 * enqueue, throwing here means no doomed outbox row is ever created — the send
 * fails soft and the caller can prompt the user to re-pick the source.
 */
class SourceUnavailableException(val path: String) :
    Exception("source no longer available: $path")
