package id.homebase.api.client.profile

import co.touchlab.kermit.Logger
import com.mayakapps.kache.FileKache
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.time.Clock

class PublicProfileProviderCached(
    private val httpClient: HttpClient,
    fileOperationsProvider: FileOperationsProvider
) {

    private val serializer = OdinSystemSerializer
    private val clock = Clock.System

    private val fileSystem = FileSystem.SYSTEM
    private val directory = fileOperationsProvider.getCacheDirectory()

    private val lock = Mutex()
    private val keyLocks = mutableMapOf<String, Mutex>()

    // Immutable set — always replaced, never mutated in-place.
    // @Volatile ensures lock-free reads always see the latest reference.
    // Writes are serialized via notFoundCacheMutex (rare: only on 404 responses).
    @Volatile private var notFoundCache: Set<String> = emptySet()
    private val notFoundCacheMutex = Mutex()

    private var _profileDiskKache: FileKache? = null
    private var _imageDiskKache: FileKache? = null
    private val kacheMutex = Mutex()

    // Always acquires kacheMutex — the previous `by lazy { runBlocking { ... } }`
    // handed out a permanent FileKache reference that a concurrent clearCaches()
    // could poison with .clear(). Mirrors DriveFileProviderCached post-fix.
    private suspend fun profileDiskKache(): FileKache = kacheMutex.withLock {
        _profileDiskKache ?: FileKache(
            directory = "$directory/homebase-public-profiles",
            maxSize = 50L * 1024L * 1024L
        ).also { _profileDiskKache = it }
    }

    private suspend fun imageDiskKache(): FileKache = kacheMutex.withLock {
        _imageDiskKache ?: FileKache(
            directory = "$directory/homebase-public-images",
            maxSize = 200L * 1024L * 1024L
        ).also { _imageDiskKache = it }
    }

    // =========================================================
    // PUBLIC API
    // =========================================================

    suspend fun getPublicProfile(odinId: OdinId): ProfileCard? =
        getCached(
            cacheKey = "profile:$odinId",
            disk = { profileDiskKache() },
            fetch = { httpClient.get("https://${odinId}/pub/profile") },
            transform = { response ->
                serializer.deserialize<ProfileCard>(response.bodyAsText())
            },
            readFromDisk = { path ->
                fileSystem.read(path.toPath()) {
                    val expiry = readLong()
                    val json = readUtf8()
                    CachedEntry(expiry, serializer.deserialize<ProfileCard>(json))
                }
            },
            writeToDisk = { path, expiry, value ->
                fileSystem.write(path.toPath()) {
                    writeLong(expiry)
                    writeUtf8(serializer.serialize(value))
                }
            }
        )

    suspend fun getPublicImage(odinId: OdinId): ByteArray? =
        getCached(
            cacheKey = "image:$odinId",
            disk = { imageDiskKache() },
            fetch = { httpClient.get("https://${odinId}/pub/image") },
            transform = { response ->
                response.bodyAsBytes()
            },
            readFromDisk = { path ->
                fileSystem.read(path.toPath()) {
                    val expiry = readLong()
                    val size = readInt()
                    val bytes = readByteArray(size.toLong())
                    CachedEntry(expiry, bytes)
                }
            },
            writeToDisk = { path, expiry, value ->
                fileSystem.write(path.toPath()) {
                    writeLong(expiry)
                    writeInt(value.size)
                    write(value)
                }
            }
        )

    suspend fun clearCaches() {
        val profileDir = "$directory/homebase-public-profiles".toPath()
        val imageDir = "$directory/homebase-public-images".toPath()

        // Serialised with the accessor functions: any in-flight reader either
        // completed before we took the mutex or is still waiting for it (and
        // will observe the post-teardown null and construct a fresh kache).
        //
        // We intentionally do NOT call FileKache.clear() — see the same-shape
        // fix in DriveFileProviderCached.clearCaches for the reason.
        kacheMutex.withLock {
            _profileDiskKache = null
            _imageDiskKache = null

            try {
                fileSystem.deleteRecursively(profileDir)
            } catch (e: Exception) {
                Logger.w(tag = "PublicProfileIO", throwable = e) { "profile cache dir delete failed" }
            }
            try {
                fileSystem.deleteRecursively(imageDir)
            } catch (e: Exception) {
                Logger.w(tag = "PublicProfileIO", throwable = e) { "image cache dir delete failed" }
            }
        }

        notFoundCache = emptySet()
    }

    // =========================================================
    // CORE CACHE ENGINE
    // =========================================================

    private suspend fun <T> getCached(
        cacheKey: String,
        disk: suspend () -> FileKache,
        fetch: suspend () -> HttpResponse,
        transform: suspend (HttpResponse) -> T,
        readFromDisk: (String) -> CachedEntry<T>,
        writeToDisk: (String, Long, T) -> Unit
    ): T? {

        if (cacheKey in notFoundCache) return null

        // Pre-mutex peek: pure read, no remove. A hit returns immediately;
        // a miss or expired entry falls through to the mutex where the
        // refresh (and any remove) is serialised.
        readCachedOrLog(disk(), cacheKey, readFromDisk)?.let { cached ->
            if (!cached.isExpired(clock)) return cached.value
        }

        val mutex = getMutex(cacheKey)

        return mutex.withLock {

            if (cacheKey in notFoundCache) return@withLock null

            readCachedOrLog(disk(), cacheKey, readFromDisk)?.let { cached ->
                if (!cached.isExpired(clock)) {
                    return@withLock cached.value
                } else {
                    try {
                        disk().remove(cacheKey)
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
                        try {
                            disk().put(cacheKey) { path ->
                                writeToDisk(path, expiry, value)
                                true
                            }
                        } catch (e: Exception) {
                            Logger.w(tag = "PublicProfileIO", throwable = e) { "cache-write failed key=$cacheKey" }
                        }
                    }

                    value
                }

                404 -> {
                    notFoundCacheMutex.withLock { notFoundCache = notFoundCache + cacheKey }
                    null
                }

                else -> throw Exception("Fetch failed: ${response.status}")
            }
        }
    }

    /**
     * Read a cached entry from [disk]. Returns null on cache miss OR when the
     * FileKache layer throws (e.g. concurrent clearCaches() on another thread)
     * OR when [readFromDisk] fails (corrupted/truncated entry). Any failure is
     * logged so the caller can fall through to the network cleanly.
     */
    private suspend fun <T> readCachedOrLog(
        disk: FileKache,
        cacheKey: String,
        readFromDisk: (String) -> CachedEntry<T>
    ): CachedEntry<T>? {
        return try {
            val path = disk.get(cacheKey) ?: return null
            readFromDisk(path)
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
