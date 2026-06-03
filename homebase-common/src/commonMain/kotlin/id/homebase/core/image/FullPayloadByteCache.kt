package id.homebase.core.image

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bounded, in-memory LRU of decrypted full-payload image bytes with in-flight
 * request coalescing.
 *
 * Why it exists:
 * - The fullscreen viewer fetches the same full payload several times per open
 *   (the base image, the subsampling tiler, and any re-decode). Coalescing +
 *   caching collapses that to a single fetch+decrypt.
 * - It backs the press-time fullscreen prefetch (a Coil preload that decodes the
 *   bitmap and, via the fetcher, populates this cache) so the viewer opens
 *   instantly.
 *
 * Security: RAM-only by design. Decrypted bytes are never written to disk — this
 * matches the deliberate `diskCache(null)` choice, and holds strictly less data
 * than the decoded bitmaps Coil already keeps in memory. Cleared via [clear]
 * from the app's existing cache-clear / logout paths.
 *
 * Concurrency: all mutable state ([entries], [inFlight], [sizeBytes]) is guarded
 * by [mutex]. The actual load runs on [scope] (not the caller), so a cancelled
 * caller (e.g. a navigated-away prefetch) does not abort a load other callers
 * are awaiting.
 */
internal class FullPayloadByteCache(
    private val maxBytes: Long,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    // Insertion-ordered map used as a manual LRU: most-recently-used is last,
    // eldest is first. (commonMain LinkedHashMap has no access-order ctor, so
    // hits are moved to the end by remove+reinsert.)
    private val entries = LinkedHashMap<String, CachedImage>()
    private val inFlight = HashMap<String, Deferred<CachedImage?>>()
    private var sizeBytes = 0L

    /**
     * Returns the cached image for [key], or loads it via [loader] exactly once
     * (coalescing concurrent callers) and caches a non-null result.
     */
    suspend fun getOrLoad(key: String, loader: suspend () -> CachedImage?): CachedImage? {
        val deferred: Deferred<CachedImage?> = mutex.withLock {
            entries[key]?.let { hit ->
                // Mark most-recently-used.
                entries.remove(key)
                entries[key] = hit
                return hit
            }
            inFlight[key] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    val result = loader()
                    mutex.withLock {
                        inFlight.remove(key)
                        if (result != null) putLocked(key, result)
                    }
                    result
                } catch (t: Throwable) {
                    // Drop the shared slot so the next caller retries instead of
                    // re-receiving this failure. Cancellation propagates too.
                    mutex.withLock { inFlight.remove(key) }
                    throw t
                }
            }.also { inFlight[key] = it }
        }
        deferred.start()
        return deferred.await()
    }

    /** Caller must hold [mutex]. Inserts as most-recently-used and evicts LRU. */
    private fun putLocked(key: String, image: CachedImage) {
        entries.remove(key)?.let { sizeBytes -= it.bytes.size }
        if (image.bytes.size > maxBytes) return // never cache an entry larger than the whole budget
        entries[key] = image
        sizeBytes += image.bytes.size
        val iterator = entries.entries.iterator()
        while (sizeBytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            sizeBytes -= eldest.value.bytes.size
            iterator.remove()
        }
    }

    suspend fun clear() {
        mutex.withLock {
            entries.clear()
            sizeBytes = 0L
        }
    }
}
