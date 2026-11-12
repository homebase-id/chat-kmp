package id.homebase.api.client

import kotlin.time.Duration

/** Not a [NetworkException]: the outbox charges and backs off a stalled row instead of re-probing it every 30 s ahead of the rest. */
class UploadStalledException(
    val idle: Duration,
    val bytesSent: Long,
    val totalBytes: Long?,
) : OdinApiException(
    status = 0,
    title = "Upload stalled: no bytes sent for $idle ($bytesSent of ${totalBytes ?: "?"} sent)",
)
