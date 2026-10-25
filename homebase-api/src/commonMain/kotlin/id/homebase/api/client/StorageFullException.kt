package id.homebase.api.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * HTTP 507: the identity is at its plan's storage quota, so the server refused new payload or thumbnail
 * bytes. Header-only writes, reads and deletes still work, and deleting files lifts it within about a
 * minute. [retryAfter] is the server's Retry-After; retrying sooner only uploads the same bytes again.
 */
class StorageFullException(
    val retryAfter: Duration?,
    problem: ProblemDetails?,
) : OdinApiException(507, problem?.title ?: "Storage full", problem?.correlationId(), problem)

/** True when [this] or anything in its cause chain is a [StorageFullException]. */
fun Throwable.isStorageFull(): Boolean = storageFullOrNull() != null

fun Throwable.storageFullOrNull(): StorageFullException? {
    val seen = HashSet<Throwable>()
    var cur: Throwable? = this
    while (cur != null && seen.add(cur)) {
        if (cur is StorageFullException) return cur
        cur = cur.cause
    }
    return null
}

internal fun retryAfterOrNull(headerValue: String?): Duration? =
    headerValue?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.seconds
