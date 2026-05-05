package id.homebase.api.client.profile

import co.touchlab.kermit.Logger
import coil3.disk.DiskCache
import id.homebase.api.client.cache.CacheStats
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.time.Clock

/**
 * Disk-backed cache for a peer identity's *public* profile data — the
 * unauthenticated JSON and avatar image served at `https://{odinId}/pub/profile`
 * and `https://{odinId}/pub/image`. Both endpoints are public HTTPS, so their
 * responses are stored on disk unencrypted.
 *
 * Two underlying [coil3.disk.DiskCache] instances back the two
 * Storage-screen rows:
 * - `public_profiles` — serialized [ProfileCard] JSON (display name, bio,
 *   links, email list, avatar URL, etc.). Directory
 *   `homebase-public-profiles-v2`, cap 10 MB.
 * - `public_images`   — raw avatar image bytes. Directory
 *   `homebase-public-images-v2`, cap 200 MB.
 *
 * The split is deliberate, not incidental. Profile JSON is small (~1–10 KB)
 * and used as a sparse fallback for identities without a local contact-drive
 * record — push senders, forward-sheet targets, unmet-peer [id.homebase.core.widget.ContactName]
 * renders. Avatar bytes are an order of magnitude larger. A single merged
 * DiskCache would let a burst of avatar loads evict profile JSON entries
 * under shared-pool LRU pressure; separate caps (10 MB profiles, 200 MB
 * images) protect the small tier from the large one regardless of relative
 * request rates. It also lets the two caps be tuned independently and
 * shows up as two distinct rows on the Storage settings screen.
 *
 * A separate in-memory [notFoundCache] records 404 responses so repeated
 * lookups of missing identities skip the network. Transient failures
 * (5xx, network errors) are never cached.
 *
 * Both DiskCache instances are constructed eagerly. Coil's DiskCache
 * (a Kotlin port of OkHttp's DiskLruCache) is thread-safe by contract,
 * so concurrent get/put/clear are all safe and no lifecycle mutex is
 * needed. See also [id.homebase.api.client.drives.cache.DriveFileProviderCached]
 * which follows the same shape.
 */
class PublicProfileProviderCached(
    private val httpClient: HttpClient,
    fileOperationsProvider: FileOperationsProvider
) {

    private val serializer = OdinSystemSerializer
    private val clock = Clock.System

    private val fileSystem = FileSystem.SYSTEM
    private val directory = fileOperationsProvider.getCacheDirectory()

    private val lock = Mutex()
    // TODO: unbounded growth — keyLocks gains one entry per unique cacheKey
    //  ever touched and never drops any. Over a long session this leaks
    //  memory. Same shape in DriveFileProviderCached. Fix with a
    //  weak-valued map or a periodic prune keyed on last-use timestamp.
    private val keyLocks = mutableMapOf<String, Mutex>()

    // Immutable set — always replaced, never mutated in-place.
    // @Volatile ensures lock-free reads always see the latest reference.
    // Writes are serialized via notFoundCacheMutex (rare: only on 404 responses).
    @Volatile private var notFoundCache: Set<String> = emptySet()
    private val notFoundCacheMutex = Mutex()

    private val profileDir = "$directory/homebase-public-profiles-v2"
    private val imageDir = "$directory/homebase-public-images-v2"

    private val profileDiskCache: DiskCache = DiskCache.Builder()
        .directory(profileDir.toPath())
        .maxSizeBytes(10L * 1024L * 1024L)
        .build()

    private val imageDiskCache: DiskCache = DiskCache.Builder()
        .directory(imageDir.toPath())
        .maxSizeBytes(200L * 1024L * 1024L)
        .build()

    init {
        // Fire-and-forget reclaim of the pre-migration mayakapps/kache cache
        // directories — see DriveFileProviderCached.init for rationale.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { fileSystem.deleteRecursively("$directory/homebase-public-profiles".toPath()) }
            runCatching { fileSystem.deleteRecursively("$directory/homebase-public-images".toPath()) }
        }
    }

    // Coil's DiskLruCache enforces `[a-z0-9_-]{1,120}` on keys; SHA-256 hex
    // gives a 64-char compliant digest with no collision risk in practice.
    private fun String.toDiskKey(): String = encodeUtf8().sha256().hex()

    // =========================================================
    // PUBLIC API
    // =========================================================

    suspend fun getPublicProfile(odinId: OdinId): ProfileCard? =
        getCached(
            cacheKey = "profile:$odinId",
            disk = profileDiskCache,
            fetch = { httpClient.get("https://${odinId}/pub/profile") },
            transform = { response ->
                serializer.deserialize<ProfileCard>(response.bodyAsText())
            },
            readFromDisk = { path ->
                fileSystem.read(path) {
                    val expiry = readLong()
                    val json = readUtf8()
                    CachedEntry(expiry, serializer.deserialize<ProfileCard>(json))
                }
            },
            writeToDisk = { path, expiry, value ->
                fileSystem.write(path) {
                    writeLong(expiry)
                    writeUtf8(serializer.serialize(value))
                }
            }
        )

    suspend fun getPublicImage(odinId: OdinId): ByteArray? =
        getCached(
            cacheKey = "image:$odinId",
            disk = imageDiskCache,
            fetch = { httpClient.get("https://${odinId}/pub/image") },
            transform = { response ->
                response.bodyAsBytes()
            },
            readFromDisk = { path ->
                fileSystem.read(path) {
                    val expiry = readLong()
                    val size = readInt()
                    val bytes = readByteArray(size.toLong())
                    CachedEntry(expiry, bytes)
                }
            },
            writeToDisk = { path, expiry, value ->
                fileSystem.write(path) {
                    writeLong(expiry)
                    writeInt(value.size)
                    write(value)
                }
            }
        )

    suspend fun clearCaches() {
        try {
            profileDiskCache.clear()
        } catch (e: Exception) {
            Logger.w(tag = "PublicProfileIO", throwable = e) { "profile cache clear failed" }
        }
        try {
            imageDiskCache.clear()
        } catch (e: Exception) {
            Logger.w(tag = "PublicProfileIO", throwable = e) { "image cache clear failed" }
        }

        notFoundCache = emptySet()
    }

    // Per-cache try/catch — see DriveFileProviderCached.getCacheStats for the
    // rationale. Same shape here for symmetry.
    suspend fun getCacheStats(): List<CacheStats> {
        val out = ArrayList<CacheStats>(2)
        try {
            out.add(CacheStats(id = "public_profiles", sizeBytes = profileDiskCache.size, maxBytes = profileDiskCache.maxSize))
        } catch (e: Throwable) {
            Logger.w(tag = "PublicProfileIO", throwable = e) { "public_profiles stats unavailable" }
            out.add(CacheStats(id = "public_profiles", sizeBytes = CacheStats.UNAVAILABLE, maxBytes = 0L))
        }
        try {
            out.add(CacheStats(id = "public_images", sizeBytes = imageDiskCache.size, maxBytes = imageDiskCache.maxSize))
        } catch (e: Throwable) {
            Logger.w(tag = "PublicProfileIO", throwable = e) { "public_images stats unavailable" }
            out.add(CacheStats(id = "public_images", sizeBytes = CacheStats.UNAVAILABLE, maxBytes = 0L))
        }
        return out
    }

    // =========================================================
    // CORE CACHE ENGINE
    // =========================================================

    private suspend fun <T> getCached(
        cacheKey: String,
        disk: DiskCache,
        fetch: suspend () -> HttpResponse,
        transform: suspend (HttpResponse) -> T,
        readFromDisk: (Path) -> CachedEntry<T>,
        writeToDisk: (Path, Long, T) -> Unit
    ): T? {

        if (cacheKey in notFoundCache) return null

        // Pre-mutex peek: pure read, no remove. A hit returns immediately;
        // a miss or expired entry falls through to the mutex where the
        // refresh (and any remove) is serialised.
        readCachedOrLog(disk, cacheKey, readFromDisk)?.let { cached ->
            if (!cached.isExpired(clock)) return cached.value
        }

        val mutex = getMutex(cacheKey)

        return mutex.withLock {

            if (cacheKey in notFoundCache) return@withLock null

            readCachedOrLog(disk, cacheKey, readFromDisk)?.let { cached ->
                if (!cached.isExpired(clock)) {
                    return@withLock cached.value
                } else {
                    try {
                        disk.remove(cacheKey.toDiskKey())
                    } catch (e: Exception) {
                        Logger.w(tag = "PublicProfileIO", throwable = e) { "remove expired entry failed key=$cacheKey" }
                    }
                }
            }

            val response = fetch()

            when (response.status.value) {

                200 -> {
                    val cacheControl = response.headers[HttpHeaders.CacheControl]
                    val ttl = extractTtlMillis(cacheControl)
                    val expiry = now() + ttl

                    val value = transform(response)

                    if (shouldStore(cacheControl)) {
                        val editor = disk.openEditor(cacheKey.toDiskKey())
                        if (editor != null) {
                            try {
                                writeToDisk(editor.data, expiry, value)
                                editor.commit()
                            } catch (e: Exception) {
                                try { editor.abort() } catch (_: Exception) {}
                                Logger.w(tag = "PublicProfileIO", throwable = e) { "cache-write failed key=$cacheKey" }
                            }
                        }
                    }

                    value
                }

                404 -> {
                    notFoundCacheMutex.withLock { notFoundCache = notFoundCache + cacheKey }
                    null
                }

                else -> {
                    Logger.w(tag = "PublicProfileIO") {
                        "unexpected status=${response.status.value} key=$cacheKey"
                    }
                    throw Exception("Fetch failed: ${response.status}")
                }
            }
        }
    }

    /**
     * Read a cached entry from [disk]. Returns null on cache miss OR when
     * [readFromDisk] fails (corrupted/truncated entry). Any failure is logged
     * so the caller can fall through to the network cleanly.
     */
    private fun <T> readCachedOrLog(
        disk: DiskCache,
        cacheKey: String,
        readFromDisk: (Path) -> CachedEntry<T>
    ): CachedEntry<T>? {
        return try {
            disk.openSnapshot(cacheKey.toDiskKey())?.use { snap -> readFromDisk(snap.data) }
        } catch (e: Exception) {
            Logger.e(tag = "PublicProfileIO", throwable = e) { "cache-read FAILED key=$cacheKey" }
            null
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private fun shouldStore(cacheControl: String?): Boolean {
        if (cacheControl == null) return true
        return !cacheControl.contains("no-store", true)
    }

    internal fun extractTtlMillis(cacheControl: String?): Long {
        val oneWeekSeconds = 7 * 24 * 60 * 60L
        val oneWeekMs = oneWeekSeconds * 1000
        if (cacheControl == null) return oneWeekMs
        val maxAge = Regex("max-age=(\\d+)")
            .find(cacheControl)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
        val ttlMs = (maxAge ?: oneWeekSeconds) * 1000
        return maxOf(ttlMs, oneWeekMs)
    }

    private fun now(): Long =
        clock.now().toEpochMilliseconds()

    private suspend fun getMutex(key: String): Mutex =
        lock.withLock { keyLocks.getOrPut(key) { Mutex() } }

    private data class CachedEntry<T>(
        val expiry: Long,
        val value: T
    ) {
        fun isExpired(clock: Clock): Boolean =
            clock.now().toEpochMilliseconds() > expiry
    }
}
