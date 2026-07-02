package id.homebase.api.client.drives.upload

/**
 * A payload staged for the outbox (a plain filesystem path, normally inside the
 * durable outbox staging dir) is missing or empty at DRAIN time.
 *
 * Thrown by [DriveUploadProvider]'s pre-flight BEFORE any network call, which is
 * what makes it deterministic and platform-independent: a missing file that
 * instead surfaces during multipart body streaming arrives as a different type
 * per platform (JVM/Android `FileNotFoundException` wrapped into a transient
 * `NetworkException` by `OdinApiProviderBase.networkCall`, okio's eager throw on
 * web, `IllegalStateException` on iOS) and burns ~48h of retries as a phantom
 * "Network failure".
 *
 * Classified PERMANENT by `OutboxFailureClassifier`: the source bytes are gone
 * and no retry brings them back, so the row is dropped on attempt 1 and the
 * user sees the failure (chat offers Retry, which re-stages from the source).
 * Never re-staged here — the original plaintext may itself be gone.
 */
class StagedPayloadMissingException(
    val path: String,
    val payloadKey: String,
) : Exception("Staged payload missing at drain: key=$payloadKey path=$path")
