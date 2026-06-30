package id.homebase.api.client.drives.cache

import co.touchlab.kermit.Logger
import coil3.disk.DiskCache
import id.homebase.api.client.ByteApiResponse
import id.homebase.api.client.cache.CacheStats
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.BytesResponse
import id.homebase.api.client.drives.files.DriveFileHelpers
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadOperationOptions
import id.homebase.api.crypto.AesCbc
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.safeDeleteRecursively
import id.homebase.api.file.systemFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import io.ktor.client.HttpClient
import io.ktor.http.Headers
import kotlin.collections.mutableMapOf
import kotlin.uuid.Uuid
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okio.ByteString.Companion.encodeUtf8
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * Disk-backed, encrypted cache for authenticated drive file bytes. Wraps
 * [DriveFileHttpProvider] so callers get transparent read-through caching
 * for payloads and thumbnails.
 *
 * Two underlying [coil3.disk.DiskCache] instances back the two
 * Storage-screen rows:
 * - `drive_payloads`   — media payload bytes (attachments). Directory
 *   `homebase-payloads-v2`, cap 200 MB. Fetches are gated by
 *   [payloadSemaphore] (1 concurrent network request).
 * - `drive_thumbnails` — thumbnail bytes. Directory `homebase-thumbs-v2`,
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
 * Both DiskCache instances are constructed eagerly. Coil's DiskCache
 * (a Kotlin port of OkHttp's DiskLruCache) is thread-safe by contract,
 * so concurrent get/put/clear are all safe and no lifecycle mutex is
 * needed. [clearCaches] calls [coil3.disk.DiskCache.clear] on each.
 */
class DriveFileProviderCached(
        httpClient: HttpClient,
        credentialsManager: CredentialsManager,
        fileOperationsProvider: FileOperationsProvider
) {
    private val delegate: DriveFileHttpProvider =
            DriveFileHttpProvider(httpClient, credentialsManager)

    private val fileSystem = systemFileSystem
    private val directory = fileOperationsProvider.getCacheDirectory()

    private val payloadSemaphore = Semaphore(1)
    private val thumbnailSemaphore = Semaphore(30)

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

    private val payloadDir = "$directory/homebase-payloads-v2"
    private val thumbDir = "$directory/homebase-thumbs-v2"

    // Coil's DiskCache (DiskLruCache descendant) is thread-safe; no lifecycle
    // mutex, no write serialization, no construction tombstones — concurrent
    // get()/put()/clear() are all safe per the Coil contract.
    private val payloadDiskCache: DiskCache = DiskCache.Builder()
            .directory(payloadDir.toPath())
            // Use the project FileSystem abstraction: okio FileSystem.SYSTEM on native (unchanged),
            // the in-memory FakeFileSystem on web. Coil's default is FileSystem.SYSTEM, which throws
            // "Javascript does not have access to the device's file system" on wasmJs.
            .fileSystem(fileSystem)
            .maxSizeBytes(200L * 1024L * 1024L) // 200MB
            .build()

    private val thumbDiskCache: DiskCache = DiskCache.Builder()
            .directory(thumbDir.toPath())
            .fileSystem(fileSystem)
            .maxSizeBytes(300L * 1024L * 1024L) // 300MB
            .build()

    init {
        // Fire-and-forget reclaim of the pre-migration mayakapps/kache cache
        // directories. Best-effort — a missing dir is fine, and any failure
        // here is purely a missed cleanup, not a correctness issue. Done off
        // the construction thread so an upgrade-day delete of a populated
        // 500 MB dir cannot block whatever path instantiates this class.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            safeDeleteRecursively(directory, "homebase-payloads")
            safeDeleteRecursively(directory, "homebase-thumbs")
        }
    }

    // Coil's DiskLruCache enforces `[a-z0-9_-]{1,120}` on keys. Our logical
    // cache keys (`payload:driveId:fileId:…`) contain colons and overflow that
    // length, so hash to a fixed-width hex digest. SHA-256 → 64 hex chars,
    // collision-resistant.
    private fun String.toDiskKey(): String = encodeUtf8().sha256().hex()

    // ================================================================
    // -------------------- CACHED PAYLOAD METHODS --------------------
    // ================================================================

    /**
     * Fetch raw payload bytes straight from the network, bypassing the disk cache. Use when the
     * caller needs the response headers the cache doesn't persist — notably
     * `SharedSecretEncryptedHeader64`, the authoritative per-payload key header for a payload whose
     * IV the server rotates on rewrite (e.g. a contact's `ext_data`).
     */
    suspend fun getPayloadBytesRawFromNetwork(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
    ): ByteApiResponse = delegate.getPayloadBytesRawNetwork(driveId, fileId, key)

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
                    writeToDiskCache(payloadDiskCache, cacheKey, "PayloadIO", result)
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
        val snapshot = payloadDiskCache.openSnapshot(cacheKey.toDiskKey())
                ?: return delegate.streamPayloadDecryptedToPath(driveId, fileId, key, keyHeader, outputPath, fileOps)

        return snapshot.use { snap ->
            val encryptedFlow = channelFlow<ByteArray> {
                val channel = this
                fileSystem.read(snap.data) {
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
            true
        }
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
                    check(result.status in 200..299) {
                        "Unexpected non-2xx status ${result.status} reached disk cache write — not caching"
                    }
                    writeToDiskCache(thumbDiskCache, cacheKey, "ThumbIO", result)
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
     * cached file cannot be parsed (corrupted entry, truncated write, etc.).
     * All exceptions are logged as errors so the caller can fall through to
     * the network cleanly.
     */
    private fun readCachedThumbOrLog(cacheKey: String): ByteApiResponse? {
        return try {
            thumbDiskCache.openSnapshot(cacheKey.toDiskKey())?.use { snap ->
                readBytesResponse(snap.data.toString())
            }
        } catch (e: Exception) {
            Logger.e(tag = "ThumbIO", throwable = e) { "thumb cache-read FAILED key=$cacheKey" }
            null
        }
    }

    /**
     * Payload-cache analog of [readCachedThumbOrLog]. Defense-in-depth against
     * parse failures.
     */
    private fun readCachedPayloadOrLog(cacheKey: String): ByteApiResponse? {
        return try {
            payloadDiskCache.openSnapshot(cacheKey.toDiskKey())?.use { snap ->
                readBytesResponse(snap.data.toString())
            }
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

    /**
     * Write a ByteApiResponse to a Coil DiskCache entry. Surface failures
     * loudly (corrupt journal, full disk) but never propagate them to the
     * caller — a cache-write failure must not poison the fresh bytes the
     * caller already paid the network round-trip for.
     */
    private fun writeToDiskCache(
            cache: DiskCache,
            cacheKey: String,
            logTag: String,
            value: ByteApiResponse
    ) {
        val editor = cache.openEditor(cacheKey.toDiskKey()) ?: return
        try {
            writeBytesResponse(editor.data.toString(), value)
            editor.commit()
        } catch (e: Exception) {
            try { editor.abort() } catch (_: Exception) {}
            Logger.e(tag = logTag, throwable = e) { "cache-write FAILED key=$cacheKey" }
        }
    }

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

    // =======================================================
    // -------------------- CACHE SEEDING --------------------
    // =======================================================

    /**
     * Seed the payload disk cache with already-encrypted bytes the client
     * produced locally (e.g. at optimistic send time, before the server has
     * the file). The bytes must be exactly what the server would return for
     * `GET payload` — AES-CBC encrypted with the file's [KeyHeader] — so that
     * [getPayloadBytesRaw]/[getPayloadBytesDecrypted] serve them
     * transparently. Clears any stale 404 marker for the key so a pre-send
     * lookup can't shadow the seeded entry.
     */
    suspend fun cachePayloadBytesEncrypted(
            driveId: Uuid,
            fileId: Uuid,
            key: String,
            bytes: ByteArray,
            contentType: String
    ) {
        val cacheKey = buildPayloadCacheKey(driveId, fileId, key, null, null)
        seedCache(payloadDiskCache, cacheKey, "PayloadIO", bytes, contentType)
    }

    /**
     * Thumbnail analog of [cachePayloadBytesEncrypted]. `lastModified` defaults
     * to null to line up with the read path's default for files that have not
     * synced back from the server yet.
     */
    suspend fun cacheThumbBytesEncrypted(
            driveId: Uuid,
            fileId: Uuid,
            payloadKey: String,
            width: Int,
            height: Int,
            bytes: ByteArray,
            contentType: String,
            lastModified: Long? = null
    ) {
        val cacheKey = buildThumbCacheKey(driveId, fileId, payloadKey, width, height, lastModified)
        seedCache(thumbDiskCache, cacheKey, "ThumbIO", bytes, contentType)
    }

    private suspend fun seedCache(
            cache: DiskCache,
            cacheKey: String,
            logTag: String,
            bytes: ByteArray,
            contentType: String
    ) {
        writeToDiskCache(
                cache,
                cacheKey,
                logTag,
                ByteApiResponse(
                        status = 200,
                        // The payloadencrypted bit is load-bearing: decrypt-on-read
                        // consults it to know the cached bytes need AES-CBC.
                        headers = Headers.build { append("payloadencrypted", "true") },
                        bytes = bytes,
                        contentType = contentType
                )
        )
        notFoundCacheMutex.withLock { notFoundCache = notFoundCache - cacheKey }
    }

    /**
     * Move a file's seeded cache entries from the client-minted optimistic
     * fileId to the server-assigned one. Counterpart to
     * [cachePayloadBytesEncrypted]/[cacheThumbBytesEncrypted]: at optimistic
     * send time entries are seeded under a random local fileId; when the file
     * syncs back the server has assigned a new fileId and readers key by it,
     * so without this the sender re-downloads media it just produced.
     *
     * [payloads] must be the SYNCED file's descriptors: thumbnail target keys
     * need the server's `lastModified` (the read path includes it in the thumb
     * key; seeds were written with null), and the thumbnail (w,h) list drives
     * which sizes to move.
     *
     * Per-entry best-effort — a missing source (LRU-evicted seed) or a failed
     * copy is logged and skipped; the only cost is a re-download.
     */
    suspend fun rekeyCachedFile(
            driveId: Uuid,
            oldFileId: Uuid,
            newFileId: Uuid,
            payloads: List<PayloadDescriptor>
    ) {
        for (descriptor in payloads) {
            moveCacheEntry(
                    cache = payloadDiskCache,
                    oldKey = buildPayloadCacheKey(driveId, oldFileId, descriptor.key, null, null),
                    newKey = buildPayloadCacheKey(driveId, newFileId, descriptor.key, null, null),
                    logTag = "PayloadIO",
            )
            for (thumb in descriptor.thumbnails.orEmpty()) {
                val w = thumb.pixelWidth ?: continue
                val h = thumb.pixelHeight ?: continue
                moveCacheEntry(
                        cache = thumbDiskCache,
                        // Seeds are written with lastModified = null; the synced
                        // descriptor's lastModified is what readers use from now on.
                        oldKey = buildThumbCacheKey(driveId, oldFileId, descriptor.key, w, h, null),
                        newKey = buildThumbCacheKey(driveId, newFileId, descriptor.key, w, h, descriptor.lastModified),
                        logTag = "ThumbIO",
                )
            }
        }
    }

    /**
     * Copy one cache entry's on-disk file to a new key, then remove the old
     * entry. A byte-exact streaming file copy — the serialized entry format
     * (status, contentType, payloadencrypted flag, bytes) is preserved without
     * ever loading a potentially large payload into memory.
     */
    private suspend fun moveCacheEntry(
            cache: DiskCache,
            oldKey: String,
            newKey: String,
            logTag: String,
    ) {
        try {
            val copied = cache.openSnapshot(oldKey.toDiskKey())?.use { snapshot ->
                val editor = cache.openEditor(newKey.toDiskKey()) ?: return@use false
                try {
                    fileSystem.source(snapshot.data).use { source ->
                        fileSystem.sink(editor.data).buffer().use { sink ->
                            sink.writeAll(source)
                        }
                    }
                    editor.commit()
                    true
                } catch (e: Exception) {
                    try { editor.abort() } catch (_: Exception) {}
                    throw e
                }
            } ?: false

            if (!copied) {
                Logger.d(tag = logTag) { "cache-rekey skipped (no source entry or editor conflict) old=$oldKey" }
                return
            }
            // A racing read between the DB fileId swap and this copy may have
            // 404-cached the new key — clear it so the moved entry is visible.
            notFoundCacheMutex.withLock { notFoundCache = notFoundCache - newKey }
            // The old key can never be read again (the local record now carries
            // the new fileId) — free its LRU budget instead of waiting for eviction.
            cache.remove(oldKey.toDiskKey())
        } catch (e: Exception) {
            Logger.w(tag = logTag, throwable = e) { "cache-rekey FAILED old=$oldKey new=$newKey" }
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
        // Coil's DiskCache.clear() is documented as thread-safe; it serialises
        // internally against in-flight readers/writers.
        try {
            payloadDiskCache.clear()
        } catch (e: Exception) {
            Logger.w(tag = "DriveFileProviderCached", throwable = e) { "payload cache clear failed" }
        }
        try {
            thumbDiskCache.clear()
        } catch (e: Exception) {
            Logger.w(tag = "DriveFileProviderCached", throwable = e) { "thumb cache clear failed" }
        }

        notFoundCache = emptySet()
    }

    // Per-cache try/catch as defense-in-depth: a thrown size read on one cache
    // must not hide the other row. Returns sentinel `sizeBytes = CacheStats.UNAVAILABLE`
    // (-1L) so the UI can render a visible "Unavailable" row when one cache is
    // unhealthy.
    suspend fun getCacheStats(): List<CacheStats> {
        val out = ArrayList<CacheStats>(2)
        try {
            out.add(CacheStats(id = "drive_payloads", sizeBytes = payloadDiskCache.size, maxBytes = payloadDiskCache.maxSize))
        } catch (e: Throwable) {
            Logger.w(tag = "DriveFileProviderCached", throwable = e) { "drive_payloads stats unavailable" }
            out.add(CacheStats(id = "drive_payloads", sizeBytes = CacheStats.UNAVAILABLE, maxBytes = 0L))
        }
        try {
            out.add(CacheStats(id = "drive_thumbnails", sizeBytes = thumbDiskCache.size, maxBytes = thumbDiskCache.maxSize))
        } catch (e: Throwable) {
            Logger.w(tag = "DriveFileProviderCached", throwable = e) { "drive_thumbnails stats unavailable" }
            out.add(CacheStats(id = "drive_thumbnails", sizeBytes = CacheStats.UNAVAILABLE, maxBytes = 0L))
        }
        return out
    }
}
