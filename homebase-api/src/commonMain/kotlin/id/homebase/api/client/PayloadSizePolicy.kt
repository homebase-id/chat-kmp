package id.homebase.api.client

/**
 * The RENDER/EXPORT boundary for downloaded payloads (#845).
 *
 * Guiding rule: the LRU payload cache only holds what the app can render
 * in-app — a payload the app can't render has no second read, so caching it
 * buys nothing and (worse) a single oversized commit makes Coil's LRU trim
 * evict every other entry and then the new entry itself.
 *
 * - Byte-array payload reads at or below [RENDER_LIMIT_BYTES] are "render"
 *   reads: allowed in RAM and admitted to the LRU disk caches.
 * - Anything larger is "export": it must be streamed to a file
 *   (`DriveFileProvider.streamPayloadDecryptedToPath` /
 *   `PayloadDownloadService.exportToTemp`) and never enters a cache.
 *
 * One number, three enforcement points — the network byte reader
 * (`OdinApiProviderBase.requestBytes`), the cache admission fence
 * (`DriveFileProviderCached.writeToDiskCache`), and the send-side seeder cap
 * (`PayloadCacheSeeder`) — all delegate here so they can't drift.
 */
object PayloadSizePolicy {
    const val RENDER_LIMIT_BYTES: Long = 50L * 1024 * 1024
}

/**
 * A byte-array payload read was refused because the body exceeds
 * [PayloadSizePolicy.RENDER_LIMIT_BYTES]. Not retryable — the payload will
 * never shrink; callers must switch to the streaming/export API
 * (`streamPayloadDecryptedToPath` / `PayloadDownloadService.exportToTemp`).
 *
 * [sizeBytes] is the server-reported Content-Length, or -1 when the response
 * had no length header and the limit was hit while reading.
 */
class PayloadTooLargeException(
    val sizeBytes: Long,
    val limitBytes: Long,
) : Exception("payload ${if (sizeBytes >= 0) "$sizeBytes bytes" else "of unknown size"} exceeds render limit $limitBytes — use the streaming export API")
