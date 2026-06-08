package id.homebase.api.client

import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lazily-built, cached pool of [createUploadHttpClient] instances bucketed by send-buffer tier.
 *
 * The OkHttp `SocketFactory` is fixed at client-build time and runs on OkHttp's own threads, so a
 * single client cannot vary `SO_SNDBUF` per request (a caller [ThreadLocal] wouldn't propagate, and
 * concurrent uploads would race a shared value). Instead we keep one client per buffer tier — each
 * with its own connection pool, so every socket inside it was created by that tier's factory. This
 * is deterministic and concurrency-safe.
 *
 * Sizing: target = `totalUploadSize / 12`, clamped to `[64 KiB, 2 MiB]`, rounded **up** to the
 * nearest power-of-two tier. `/12` yields ~12 wire-paced `onUpload` steps; the 2 MiB ceiling stays
 * above the bandwidth-delay product of typical mobile/Wi-Fi links so large uploads aren't
 * over-throttled; the 64 KiB floor bounds the throughput penalty for tiny payloads (which finish
 * sub-second anyway). Only the multipart upload path uses this — all other requests keep the shared
 * client.
 */
class UploadHttpClientPool {
    private val mutex = Mutex()
    private val clientsByTier = mutableMapOf<Int, HttpClient>()

    /** The send-buffer tier (bytes) that [clientFor] would use for [totalUploadSize]. */
    fun bufferBytesFor(totalUploadSize: Long): Int = tierFor(totalUploadSize)

    /** Returns the cached upload client for [totalUploadSize]'s tier, building it on first use. */
    suspend fun clientFor(totalUploadSize: Long): HttpClient {
        val tier = tierFor(totalUploadSize)
        return mutex.withLock {
            clientsByTier.getOrPut(tier) { createUploadHttpClient(tier) }
        }
    }

    companion object {
        private const val MIN_BUFFER = 64 * 1024          // 64 KiB
        private const val MAX_BUFFER = 2 * 1024 * 1024    // 2 MiB

        private val TIERS = intArrayOf(
            64 * 1024,        // 64 KiB
            128 * 1024,       // 128 KiB
            256 * 1024,       // 256 KiB
            512 * 1024,       // 512 KiB
            1024 * 1024,      // 1 MiB
            2 * 1024 * 1024,  // 2 MiB
        )

        internal fun tierFor(totalUploadSize: Long): Int {
            val target = (totalUploadSize / 12).coerceIn(MIN_BUFFER.toLong(), MAX_BUFFER.toLong())
            return TIERS.firstOrNull { it.toLong() >= target } ?: MAX_BUFFER
        }
    }
}
