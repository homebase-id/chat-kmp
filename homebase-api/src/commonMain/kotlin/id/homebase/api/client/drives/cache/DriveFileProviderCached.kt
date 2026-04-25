package id.homebase.api.client.drives.cache

import co.touchlab.kermit.Logger
import com.mayakapps.kache.FileKache
import id.homebase.api.client.ByteApiResponse
import id.homebase.api.client.cache.CacheStats
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.BytesResponse
import id.homebase.api.client.drives.files.DriveFileHelpers
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.PayloadOperationOptions
import id.homebase.api.crypto.AesCbc
import id.homebase.api.file.FileOperationsProvider
import kotlinx.coroutines.flow.channelFlow
import io.ktor.client.HttpClient
import io.ktor.http.Headers
import kotlin.collections.mutableMapOf
import kotlin.uuid.Uuid
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * Disk-backed, encrypted cache for authenticated drive file bytes. Wraps
 * [DriveFileHttpProvider] so callers get transparent read-through caching
 * for payloads and thumbnails.
 *
 * Two underlying [com.mayakapps.kache.FileKache] instances back the two
 * Storage-screen rows:
 * - `drive_payloads`   — media payload bytes (attachments). Directory
 *   `homebase-payloads`, cap 200 MB. Fetches are gated by
 *   [payloadSemaphore] (1 concurrent network request).
 * - `drive_thumbnails` — thumbnail bytes. Directory `homebase-thumbs`,
 *   cap 300 MB. Fetches are gated by [thumbnailSemaphore] (30 concurrent).
 *
 * The payload/thumbnail split follows the same "small-hot vs large-cold"
 * stratification as PublicProfileProviderCached — thumbnails render on
 * every gallery scroll, so they are kept on their own cap where a burst
 * of full-payload fetches cannot evict them.
 *
 * Bytes are AES-CBC-encrypted on the wire and written encrypted to disk;
 * the [KeyHeader] is *not* persisted to the cache, so a copy of the cache
 * directory alone yields no plaintext.
 *
 * A separate in-memory [notFoundCache] records 404 responses so repeated
 * lookups of deleted files skip the network. Transient failures
 * (5xx, network errors) are never cached.
 *
 * Unlike [id.homebase.api.client.profile.PublicProfileProviderCached], this
 * cache has no TTL or `Cache-Control` handling: drive file bytes are
 * immutable at a given `(driveId, fileId, key, chunkStart, chunkLength,
 * lastModified)` tuple, so the cacheKey itself is version-addressed.
 * A new version lands under a new key, which gets fetched fresh; the old
 * key ages out through LRU eviction.
 *
 * Both FileKache instances are created lazily through mutex-gated
 * accessors; [clearCaches] deletes the directories recursively and nulls
 * the refs, so concurrent readers cannot end up with a disposed
 * FileKache reference — they re-create it on next access.
 */
class DriveFileProviderCached(
        httpClient: HttpClient,
        credentialsManager: CredentialsManager,
        fileOperationsProvider: FileOperationsProvider
) {
    private val delegate: DriveFileHttpProvider =
            DriveFileHttpProvider(httpClient, credentialsManager)

    private val fileSystem = FileSystem.SYSTEM
    private val directory = fileOperationsProvider.getCacheDirectory()

    private val payloadSemaphore = Semaphore(1)
    private val thumbnailSemaphore = Semaphore(30)

    private val thumbnailKacheWriteMutex = Mutex()

    // TODO: unbounded growth — keyLocks gains one entry per unique cacheKey
    //  ever touched and never drops any. Over a long session this leaks
    //  memory. Same shape in PublicProfileProviderCached. Fix with a
    //  weak-valued map or a periodic prune keyed on last-use timestamp.
    private val keyLocks = mutableMapOf<String, Mutex>()
    private val lock = Mutex()

    // Immutable set — always replaced, never mutated in-place.
    // @Volatile ensures lock-free reads always see the latest reference.
    // Writes are serialized via notFoundCacheMutex (rare: only on 404 responses).
    // Only 404 (NotFoundException) is cached. Transient failures (5xx, network errors) are never cached.
    @Volatile private var notFoundCache: Set<String> = emptySet()
    private val notFoundCacheMutex = Mutex()

    private var _payloadDiskKache: FileKache? = null
    private var _thumbDiskKache: FileKache? = null
    @Volatile private var payloadKacheFailure: Throwable? = null
    @Volatile private var thumbKacheFailure: Throwable? = null
    private val kacheMutex = Mutex()

    // Always acquires kacheMutex — the previous lock-free fast path let a
    // reader hand out a reference to a FileKache instance that clearCaches()
    // was simultaneously disposing, producing a NPE inside FileKache's
    // internals when the reader resumed and called .get() on it.
    //
    // createDirectories + tombstone on construction failure: observed on one
    // real device that FileKache(…) itself throws `getClass() on null` from
    // mayakapps/kache internals when the managed directory is missing.
    // We pre-create the dir to avoid the library's fragile init path, and
    // record any construction exception so we don't spam retries for the
    // rest of the session. clearCaches() resets the tombstone.
    private suspend fun payloadDiskKache(): FileKache = kacheMutex.withLock {
        _payloadDiskKache?.let { return@withLock it }
        payloadKacheFailure?.let { throw it }

        val dir = "$directory/homebase-payloads"
        try {
            fileSystem.createDirectories(dir.toPath())
            FileKache(directory = dir, maxSize = 200L * 1024L * 1024L) // 200MB
                    .also { _payloadDiskKache = it }
        } catch (e: Throwable) {
            Logger.e(tag = "DriveFileProviderCached", throwable = e) {
                "FileKache construction FAILED (payload) — disabling disk cache for this session"
            }
            payloadKacheFailure = e
            throw e
        }
    }

    private suspend fun thumbDiskKache(): FileKache = kacheMutex.withLock {
        _thumbDiskKache?.let { return@withLock it }
        thumbKacheFailure?.let { throw it }

        val dir = "$directory/homebase-thumbs"
        try {
            fileSystem.createDirectories(dir.toPath())
            FileKache(directory = dir, maxSize = 300L * 1024L * 1024L) // 300MB
                    .also { _thumbDiskKache = it }
        } catch (e: Throwable) {
            Logger.e(tag = "DriveFileProviderCached", throwable = e) {
                "FileKache construction FAILED (thumb) — disabling disk cache for this session"
            }
            thumbKacheFailure = e
            throw e
        }
    }

    // ================================================================
    // -------------------- CACHED PAYLOAD METHODS --------------------
    // ================================================================

    suspend fun getPayloadBytesRaw(
            driveId: Uuid,
            fileId: Uuid,
            key: String,
            options: PayloadOperationOptions = PayloadOperationOptions(),
            onDownloadProgress: ((Float) -> Unit)? = null,
    ): ByteApiResponse {
        val cacheKey =
                buildPayloadCacheKey(driveId, fileId, key, options.chunkStart, options.chunkLength)

        // 1️⃣ Check in-memory 404 cache first
        if (cacheKey in notFoundCache) {
            return ByteApiResponse.EMPTY_404
        }

        // 2️⃣ Peek in disk cache and return result if it's there
        readCachedPayloadOrLog(cacheKey)?.let { return it }

        // 2️⃣ Fetch from network but lock to make sure that we don't load the same
        // resource twice over the network
        val mutex: Mutex
        lock.withLock { mutex = keyLocks.getOrPut(cacheKey) { Mutex() } }

        return mutex.withLock {
            // Re-try caches JIC there's a thread race
            if (cacheKey in notFoundCache) {
                return@withLock ByteApiResponse.EMPTY_404
            }
            readCachedPayloadOrLog(cacheKey)?.let { return@withLock it }

            // we allow up to 3 concurrent semaphore payloads over the network
            return payloadSemaphore.withPermit {
                try {
                    val result = delegate.getPayloadBytesRawNetwork(driveId, fileId, key, options, onDownloadProgress)

                    // 3️⃣ Store to disk (only 200/206 can reach here — throwForFailure throws for everything else)
                    check(result.status in 200..299) {
                        "Unexpected non-2xx status ${result.status} reached disk cache write — not caching"
                    }
                    try {
                        payloadDiskKache().put(cacheKey) { filePath ->
                            writeBytesResponse(filePath, result)
                        }
                    } catch (e: Exception) {
                        // A write failure should not prevent the caller from getting the fetched bytes.
                        // Surface it loudly so we can spot corrupted journals or full disks.
                        Logger.e(tag = "PayloadIO", throwable = e) { "payload cache-write FAILED key=$cacheKey" }
                    }
                    result
                } catch (e: NotFoundException) {
                    // 404 thrown by network layer — cache it so future calls skip the network
                    notFoundCacheMutex.withLock { notFoundCache = notFoundCache + cacheKey }
                    throw e
                } catch (e: Exception) {
                    // For other errors (500, network issues, etc.), don't cache and rethrow
                    Logger.w(tag = "PayloadIO") { "payload network-fetch FAILED (${e::class.simpleName}): ${e.message} key=$cacheKey" }
                    throw e
                }
            }
        } // Mutex.lock
    }

    suspend fun getPayloadBytesDecrypted(
            driveId: Uuid,
            fileId: Uuid,
            key: String,
            keyHeader: KeyHeader,
            chunkStart: Long? = null,
            chunkLength: Long? = null,
            onDownloadProgress: ((Float) -> Unit)? = null,
    ): BytesResponse? {
        val raw =
                getPayloadBytesRaw(
                        driveId = driveId,
                        fileId = fileId,
                        key = key,
                        options =
                                PayloadOperationOptions(
                                        chunkStart = chunkStart,
                                        chunkLength = chunkLength
                                ),
                        onDownloadProgress = onDownloadProgress,
                )

        if (raw.status == 404) throw NotFoundException()

        val rangeResult = DriveFileHelpers.getRangeHeader(chunkStart, chunkLength)

        val decryptedBytes = if (rangeResult.updatedChunkStart != null) {
            val decrypted =
                    delegate.decryptChunkedBytes(
                            raw.headers,
                            raw.bytes,
                            keyHeader,
                            startOffset = rangeResult.startOffset,
                            chunkStart = (chunkStart ?: 0).toInt()
                    )

            val sliceEnd = chunkLength?.toInt() ?: decrypted.size
            decrypted.sliceArray(0 until minOf(sliceEnd, decrypted.size))
        } else {
            delegate.decryptBytes(keyHeader, raw.headers, raw.bytes)
        }

        return BytesResponse(bytes = decryptedBytes, contentType = raw.contentType)
    }

    suspend fun streamPayloadDecryptedToPath(
            driveId: Uuid,
            fileId: Uuid,
            key: String,
            keyHeader: KeyHeader,
            outputPath: String,
            fileOps: FileOperationsProvider
    ): Boolean {
        val cacheKey = buildPayloadCacheKey(driveId, fileId, key, null, null)
        val cachedFilePath = payloadDiskKache().get(cacheKey)
                ?: return delegate.streamPayloadDecryptedToPath(driveId, fileId, key, keyHeader, outputPath, fileOps)

        val encryptedFlow = channelFlow<ByteArray> {
            val channel = this
            fileSystem.read(cachedFilePath.toPath()) {
                readInt() // skip status
                val ctLen = readInt()
                readUtf8(ctLen.toLong()) // skip contentType
                readByte() // skip payloadEncrypted flag
                val chunkSize = 65_536L
                while (true) {
                    if (!request(chunkSize)) {
                        if (!exhausted()) channel.send(readByteArray())
                        break
                    }
                    channel.send(readByteArray(chunkSize))
                }
            }
        }

        val decryptedFlow = AesCbc.streamDecryptWithCbc(encryptedFlow, keyHeader.aesKey, keyHeader.iv)
        fileOps.writeStream(outputPath, decryptedFlow)
        return true
    }

    // ==============================================================
    // -------------------- CACHED THUMB METHODS --------------------
    // ==============================================================

    suspend fun getThumbBytesRaw(
            driveId: Uuid,
            fileId: Uuid,
            payloadKey: String,
            width: Int,
            height: Int,
            lastModified: Long? = null
    ): ByteApiResponse {
        val cacheKey = buildThumbCacheKey(driveId, fileId, payloadKey, width, height, lastModified)

        // 1️⃣ Check in-memory 404 cache first
        if (cacheKey in notFoundCache) return ByteApiResponse.EMPTY_404

        // 2️⃣ Peek in disk cache and return result if it's there
        readCachedThumbOrLog(cacheKey)?.let { return it }

        // 2️⃣ Fetch from network but lock to make sure that we don't load the same
        // resource twice over the network
        val mutex: Mutex
        lock.withLock { mutex = keyLocks.getOrPut(cacheKey) { Mutex() } }

        return mutex.withLock {
            // Re-try caches JIC there's a thread race
            if (cacheKey in notFoundCache) {
                return@withLock ByteApiResponse.EMPTY_404
            }
            readCachedThumbOrLog(cacheKey)?.let { return@withLock it }

            // we allow up to 30 concurrent semaphore thumbnails over the network
            return thumbnailSemaphore.withPermit {
                try {
                    val result =
                            delegate.getThumbBytesRawNetwork(
                                    driveId,
                                    fileId,
                                    payloadKey,
                                    width,
                                    height,
                                    lastModified
                            )

                    // 3️⃣ Store to disk (only 200/206 can reach here — throwForFailure throws for everything else).
                    // Serialise writes to avoid corrupting FileKache's journal file.
                    check(result.status in 200..299) {
                        "Unexpected non-2xx status ${result.status} reached disk cache write — not caching"
                    }
                    try {
                        thumbnailKacheWriteMutex.withLock {
                            thumbDiskKache().put(cacheKey) { filePath ->
                                writeBytesResponse(filePath, result)
                            }
                        }
                    } catch (e: Exception) {
                        // A write failure should not prevent the caller from getting the fetched bytes.
                        // Surface it loudly so we can spot corrupted journals or full disks.
                        Logger.e(tag = "ThumbIO", throwable = e) { "thumb cache-write FAILED key=$cacheKey" }
                    }
                    result
                } catch (e: NotFoundException) {
                    // 404 thrown by network layer — cache it so future calls skip the network
                    notFoundCacheMutex.withLock { notFoundCache = notFoundCache + cacheKey }
                    throw e
                } catch (e: Exception) {
                    // For other errors (500, network issues, etc.), don't cache and rethrow
                    Logger.w(tag = "ThumbIO") { "thumb network-fetch FAILED (${e::class.simpleName}): ${e.message} key=$cacheKey" }
                    throw e
                }
            }
        } // Mutex.lock
    }

    /**
     * Read a thumb from the disk cache. Returns null on cache miss OR when the
     * cached file cannot be parsed (corrupted entry, truncated write, etc.) OR
     * when the underlying FileKache throws (e.g. a concurrent clearCaches()
     * on another thread). All exceptions are logged as errors so the caller
     * can fall through to the network cleanly.
     */
    private suspend fun readCachedThumbOrLog(cacheKey: String): ByteApiResponse? {
        return try {
            val filePath = thumbDiskKache().get(cacheKey) ?: return null
            readBytesResponse(filePath)
        } catch (e: Exception) {
            Logger.e(tag = "ThumbIO", throwable = e) { "thumb cache-read FAILED key=$cacheKey" }
            null
        }
    }

    /**
     * Payload-cache analog of [readCachedThumbOrLog]. Defense-in-depth against
     * FileKache internal errors and parse failures.
     */
    private suspend fun readCachedPayloadOrLog(cacheKey: String): ByteApiResponse? {
        return try {
            val filePath = payloadDiskKache().get(cacheKey) ?: return null
            val result = readBytesResponse(filePath)
            result
        } catch (e: Exception) {
            Logger.e(tag = "PayloadIO", throwable = e) { "payload cache-read FAILED key=$cacheKey" }
            null
        }
    }

    suspend fun getThumbBytesDecrypted(
            driveId: Uuid,
            fileId: Uuid,
            payloadKey: String,
            keyHeader: KeyHeader,
            width: Int,
            height: Int,
            lastModified: Long? = null
    ): BytesResponse? {
        val raw =
                getThumbBytesRaw(
                        driveId = driveId,
                        fileId = fileId,
                        payloadKey = payloadKey,
                        width = width,
                        height = height,
                        lastModified = lastModified
                )

        if (raw.status == 404) throw NotFoundException()

        val payloadEncryptedHeader = raw.headers["payloadencrypted"]
        val decryptedBytes = try {
            delegate.decryptBytes(keyHeader, raw.headers, raw.bytes)
        } catch (e: Exception) {
            // Single most likely NPE site when the cache is poisoned with a
            // non-2xx response from the pre-61ebe154 code path. Keep context
            // so the log pins the bad key.
            Logger.e(tag = "ThumbIO", throwable = e) {
                "thumb decrypt FAILED (${e::class.simpleName}): status=${raw.status} " +
                    "bytes=${raw.bytes.size} contentType=${raw.contentType} " +
                    "payloadEncrypted=$payloadEncryptedHeader drive=$driveId file=$fileId " +
                    "key=$payloadKey size=${width}x$height lastMod=$lastModified"
            }
            throw e
        }

        return BytesResponse(bytes = decryptedBytes, contentType = raw.contentType)
    }

    // =================================================
    // -------------------- FILE IO --------------------
    // =================================================

    private fun writeBytesResponse(filePath: String, value: ByteApiResponse): Boolean {
        val path = filePath.toPath()

        // Extract the payloadencrypted header - this is critical for decryption on cache reads
        val payloadEncrypted =
                value.headers["payloadencrypted"]?.equals("true", ignoreCase = true) == true

        fileSystem.write(path) {
            writeInt(value.status)
            writeInt(value.contentType.length)
            writeUtf8(value.contentType)
            // Store whether the payload is encrypted (1 = true, 0 = false)
            writeByte(if (payloadEncrypted) 1 else 0)
            write(value.bytes)
        }

        return true
    }

    private fun readBytesResponse(filePath: String): ByteApiResponse {
        val path = filePath.toPath()

        return fileSystem.read(path) {
            val status = readInt()
            val contentTypeLength = readInt()
            val contentType = readUtf8(contentTypeLength.toLong())
            val payloadEncrypted = readByte() == 1.toByte()
            val bytes = readByteArray()

            // Reconstruct the payloadencrypted header for decryption logic
            val headers =
                    if (payloadEncrypted) {
                        Headers.build { append("payloadencrypted", "true") }
                    } else {
                        Headers.Empty
                    }

            ByteApiResponse(status, headers, bytes, contentType)
        }
    }

    // ====================================================
    // -------------------- CACHE KEYS --------------------
    // ====================================================

    private fun buildPayloadCacheKey(
            driveId: Uuid,
            fileId: Uuid,
            key: String,
            chunkStart: Long?,
            chunkLength: Long?
    ): String =
            listOf("payload", driveId, fileId, key, chunkStart ?: "full", chunkLength ?: "full")
                    .joinToString(":")

    private fun buildThumbCacheKey(
            driveId: Uuid,
            fileId: Uuid,
            payloadKey: String,
            width: Int,
            height: Int,
            lastModified: Long?
    ): String =
            listOf("thumb", driveId, fileId, payloadKey, width, height, lastModified ?: "null")
                    .joinToString(":")

    suspend fun clearCaches() {
        val payloadDir = "$directory/homebase-payloads".toPath()
        val thumbDir = "$directory/homebase-thumbs".toPath()
        val preloadDir = "$directory/hbvid_preload".toPath()

        // Serialised with the accessor functions: any in-flight payload/thumb
        // reader either completed before we took the mutex (so its reference
        // is no longer reachable from us) or is still waiting for it (so it
        // will observe the post-teardown null and construct a fresh kache).
        //
        // We intentionally do NOT call FileKache.clear() — on at least one
        // Android device that call raced with concurrent .get() calls and
        // nulled internal FileKache state under a reader's feet, producing
        // the `getClass() on null` NPE that fired 335× after logout.
        // Deleting the directory directly achieves the same outcome and the
        // next accessor reconstructs a clean FileKache on top of an empty dir.
        kacheMutex.withLock {
            _payloadDiskKache = null
            _thumbDiskKache = null
            // Reset the ctor tombstones. This is the recovery path for a
            // FileKache-ctor NPE (observed on real devices with a populated
            // homebase-thumbs dir from a prior install): the Storage-screen
            // "Clear caches" button deletes the directory and clears the
            // tombstone so the next access builds a fresh FileKache on top
            // of an empty dir.
            payloadKacheFailure = null
            thumbKacheFailure = null

            try {
                fileSystem.deleteRecursively(payloadDir)
            } catch (e: Exception) {
                Logger.w(tag = "DriveFileProviderCached", throwable = e) { "payload cache dir delete failed" }
            }
            try {
                fileSystem.deleteRecursively(thumbDir)
            } catch (e: Exception) {
                Logger.w(tag = "DriveFileProviderCached", throwable = e) { "thumb cache dir delete failed" }
            }
        }

        try {
            fileSystem.deleteRecursively(preloadDir)
        } catch (e: Exception) {
            Logger.w(tag = "DriveFileProviderCached", throwable = e) { "hbvid_preload dir delete failed" }
        }

        notFoundCache = emptySet()
    }

    // Per-cache try/catch: one broken FileKache (e.g. ctor tombstoned after a
    // mayakapps/kache NPE on this device's pre-existing cache state) must not
    // hide the other row. A thrown `payloadDiskKache()` used to propagate up
    // through the ViewModel's outer runCatching and wipe *both* drive rows
    // from the Storage screen, leaving the user with no indication that a
    // cache was unhealthy. Return sentinel `sizeBytes = CacheStats.UNAVAILABLE`
    // (-1L) so the UI can render a visible "Unavailable" row and point the
    // user at the Clear caches button — which resets the tombstone.
    suspend fun getCacheStats(): List<CacheStats> {
        val out = ArrayList<CacheStats>(2)
        try {
            val payload = payloadDiskKache()
            out.add(CacheStats(id = "drive_payloads", sizeBytes = payload.size, maxBytes = payload.maxSize))
        } catch (e: Throwable) {
            Logger.w(tag = "DriveFileProviderCached", throwable = e) { "drive_payloads stats unavailable" }
            out.add(CacheStats(id = "drive_payloads", sizeBytes = CacheStats.UNAVAILABLE, maxBytes = 0L))
        }
        try {
            val thumb = thumbDiskKache()
            out.add(CacheStats(id = "drive_thumbnails", sizeBytes = thumb.size, maxBytes = thumb.maxSize))
        } catch (e: Throwable) {
            Logger.w(tag = "DriveFileProviderCached", throwable = e) { "drive_thumbnails stats unavailable" }
            out.add(CacheStats(id = "drive_thumbnails", sizeBytes = CacheStats.UNAVAILABLE, maxBytes = 0L))
        }
        return out
    }
}
