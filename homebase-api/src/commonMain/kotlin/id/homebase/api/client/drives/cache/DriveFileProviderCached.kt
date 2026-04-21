package id.homebase.api.client.drives.cache

import co.touchlab.kermit.Logger
import com.mayakapps.kache.FileKache
import kotlin.time.measureTimedValue
import id.homebase.api.client.ByteApiResponse
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
    private val kacheMutex = Mutex()

    // Always acquires kacheMutex — the previous lock-free fast path let a
    // reader hand out a reference to a FileKache instance that clearCaches()
    // was simultaneously disposing, producing a NPE inside FileKache's
    // internals when the reader resumed and called .get() on it.
    private suspend fun payloadDiskKache(): FileKache = kacheMutex.withLock {
        _payloadDiskKache ?: FileKache(
                directory = "$directory/homebase-payloads",
                maxSize = 200L * 1024L * 1024L // 200MB
        ).also { _payloadDiskKache = it }
    }

    private suspend fun thumbDiskKache(): FileKache = kacheMutex.withLock {
        _thumbDiskKache ?: FileKache(
                directory = "$directory/homebase-thumbs",
                maxSize = 300L * 1024L * 1024L // 300MB
        ).also { _thumbDiskKache = it }
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
                    val (networkResult, elapsed) = measureTimedValue {
                        delegate.getPayloadBytesRawNetwork(driveId, fileId, key, options, onDownloadProgress)
                    }
                    Logger.d(tag = "VideoIO") { "payload network-fetch: ${networkResult.bytes.size} bytes in $elapsed key=$cacheKey" }
                    val result = networkResult

                    // 3️⃣ Store to disk (only 200/206 can reach here — throwForFailure throws for everything else)
                    check(result.status in 200..299) {
                        "Unexpected non-2xx status ${result.status} reached disk cache write — not caching"
                    }
                    val (_, cacheWriteElapsed) = measureTimedValue {
                        payloadDiskKache().put(cacheKey) { filePath ->
                            writeBytesResponse(filePath, result)
                        }
                    }
                    Logger.d(tag = "VideoIO") { "payload cache-write: ${result.bytes.size} bytes in $cacheWriteElapsed key=$cacheKey" }
                    result
                } catch (e: NotFoundException) {
                    // 404 thrown by network layer — cache it so future calls skip the network
                    notFoundCacheMutex.withLock { notFoundCache = notFoundCache + cacheKey }
                    throw e
                } catch (e: Exception) {
                    // For other errors (500, network issues, etc.), don't cache and rethrow
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

        val (decryptedBytes, decryptElapsed) =
                measureTimedValue {
                    if (rangeResult.updatedChunkStart != null) {
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
                }
        Logger.d(tag = "VideoIO") { "payload decrypt: ${raw.bytes.size} → ${decryptedBytes.size} bytes in $decryptElapsed" }

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
        if (cacheKey in notFoundCache) {
            Logger.d(tag = "ThumbIO") { "thumb notFound-cache-hit key=$cacheKey" }
            return ByteApiResponse.EMPTY_404
        }

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
                    Logger.d(tag = "ThumbIO") {
                        "thumb network-fetch: status=${result.status} ${result.bytes.size} bytes contentType=${result.contentType} key=$cacheKey"
                    }

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
                        Logger.d(tag = "ThumbIO") { "thumb cache-write: ${result.bytes.size} bytes key=$cacheKey" }
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
            val result = readBytesResponse(filePath)
            Logger.d(tag = "ThumbIO") { "thumb cache-hit: status=${result.status} ${result.bytes.size} bytes key=$cacheKey" }
            result
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
            val (result, elapsed) = measureTimedValue { readBytesResponse(filePath) }
            Logger.d(tag = "VideoIO") { "payload cache-hit: ${result.bytes.size} bytes in $elapsed key=$cacheKey" }
            result
        } catch (e: Exception) {
            Logger.e(tag = "VideoIO", throwable = e) { "payload cache-read FAILED key=$cacheKey" }
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
        } catch (_: Exception) {}

        notFoundCache = emptySet()
    }
}
