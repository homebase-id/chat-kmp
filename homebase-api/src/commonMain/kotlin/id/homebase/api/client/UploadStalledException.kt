package id.homebase.api.client

import kotlin.time.Duration

/**
 * An upload whose body stopped moving: the connection was made, then no bytes went out for [idle].
 * Not a [NetworkException], so the outbox charges the attempt and backs off instead of treating it as
 * "offline" and retrying the same row every 30 s ahead of everything queued behind it.
 */
class UploadStalledException(
    val idle: Duration,
    val bytesSent: Long,
    val totalBytes: Long?,
) : OdinApiException(
    status = 0,
    title = "Upload stalled: no bytes sent for $idle ($bytesSent of ${totalBytes ?: "?"} sent)",
)
