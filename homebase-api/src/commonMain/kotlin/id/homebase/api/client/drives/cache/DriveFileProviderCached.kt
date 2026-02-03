package id.homebase.api.client.drives.cache

import com.mayakapps.kache.FileKache
import id.homebase.api.client.ByteApiResponse
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.BytesResponse
import id.homebase.api.client.drives.files.DriveFileHelpers
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.PayloadOperationOptions
import io.ktor.client.HttpClient
import io.ktor.http.Headers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.collections.mutableMapOf
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.uuid.Uuid


class DriveFileProviderCached(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager
) {
    private val delegate: DriveFileHttpProvider =
        DriveFileHttpProvider(httpClient, credentialsManager)

    private val fileSystem = FileSystem.SYSTEM

    private val payloadSemaphore = Semaphore(1)
    private val thumbnailSemaphore = Semaphore(30)
    private val keyLocks = mutableMapOf<String, Mutex>()
    private val lock = Mutex()

    // In-memory cache for 404 responses
    // Later we should cache 401,403,404,410, but not yet
    private val notFoundCache = mutableSetOf<String>()

    private val payloadDiskKache by lazy {
        kotlinx.coroutines.runBlocking {
            FileKache(
                directory = "homebase-payloads",
                maxSize = 200L * 1024L * 1024L // 200MB
            )
        }
    }

    private val thumbDiskKache by lazy {
        kotlinx.coroutines.runBlocking {
            FileKache(
                directory = "homebase-thumbs",
                maxSize = 300L * 1024L * 1024L  // 300MB
            )
        }
    }

    // ================================================================
    // -------------------- CACHED PAYLOAD METHODS --------------------
    // ================================================================

    suspend fun getPayloadBytesRaw(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        options: PayloadOperationOptions = PayloadOperationOptions()
    ): ByteApiResponse {
        val cacheKey =
            buildPayloadCacheKey(
                driveId,
                fileId,
                key,
                options.chunkStart,
                options.chunkLength
            )

        // 1️⃣ Check in-memory 404 cache first
        if (cacheKey in notFoundCache) {
            return ByteApiResponse(404, Headers.Empty, ByteArray(0), "application/octet-stream")
        }

        // 2️⃣ Peek in disk cache and return result if it's there
        payloadDiskKache.get(cacheKey)?.let { filePath ->
            return readBytesResponse(filePath)
        }

        // 2️⃣ Fetch from network but lock to make sure that we don't load the same
        // resource twice over the network
        val mutex: Mutex
        lock.withLock {
            mutex = keyLocks.getOrPut(cacheKey) { Mutex() }
        }

        return mutex.withLock {
            // Re-try caches JIC there's a thread race
            if (cacheKey in notFoundCache) {
                return@withLock ByteApiResponse(
                    404,
                    Headers.Empty,
                    ByteArray(0),
                    "application/octet-stream"
                )
            }
            payloadDiskKache.get(cacheKey)?.let { filePath ->
                return@withLock readBytesResponse(filePath)
            }

            // we allow up to 3 concurrent semaphore payloads over the network
            return payloadSemaphore.withPermit {
                try {
                    val result =
                        delegate.getPayloadBytesRawNetwork(
                            driveId,
                            fileId,
                            key,
                            options
                        )

                    if (result.status == 404) {
                        // 404 case - cache that file doesn't exist in memory only
                        notFoundCache.add(cacheKey)
                        ByteApiResponse(
                            404,
                            Headers.Empty,
                            ByteArray(0),
                            "application/octet-stream"
                        )
                    } else {
                        // 3️⃣ Store to disk
                        payloadDiskKache.put(cacheKey) { filePath ->
                            writeBytesResponse(filePath, result)
                        }
                        result
                    }
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
        chunkStart: Long? = null,
        chunkLength: Long? = null
    ): BytesResponse? {
        val raw =
            getPayloadBytesRaw(
                driveId = driveId,
                fileId = fileId,
                key = key,
                options = PayloadOperationOptions(
                    chunkStart = chunkStart,
                    chunkLength = chunkLength
                )
            )

        if (raw.status == 404) return null

        val rangeResult =
            DriveFileHelpers.getRangeHeader(chunkStart, chunkLength)

        val decryptedBytes =
            if (rangeResult.updatedChunkStart != null) {
                val decrypted =
                    delegate.decryptChunkedBytes(
                        raw.headers,
                        raw.bytes,
                        startOffset = rangeResult.startOffset,
                        chunkStart = (chunkStart ?: 0).toInt()
                    )

                val sliceEnd =
                    if (chunkLength != null && chunkStart != null) {
                        (chunkLength - chunkStart).toInt()
                    } else {
                        decrypted.size
                    }

                decrypted.sliceArray(0 until minOf(sliceEnd, decrypted.size))
            } else {
                delegate.decryptBytes(raw.headers, raw.bytes)
            }

        return BytesResponse(
            bytes = decryptedBytes,
            contentType = raw.contentType
        )
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
        val cacheKey =
            buildThumbCacheKey(
                driveId,
                fileId,
                payloadKey,
                width,
                height,
                lastModified
            )

        // 1️⃣ Check in-memory 404 cache first
        if (cacheKey in notFoundCache) {
            return ByteApiResponse(404, Headers.Empty, ByteArray(0), "application/octet-stream")
        }

        // 2️⃣ Peek in disk cache and return result if it's there
        thumbDiskKache.get(cacheKey)?.let { filePath ->
            return readBytesResponse(filePath)
        }

        // 2️⃣ Fetch from network but lock to make sure that we don't load the same
        // resource twice over the network
        val mutex: Mutex
        lock.withLock {
            mutex = keyLocks.getOrPut(cacheKey) { Mutex() }
        }

        return mutex.withLock {
            // Re-try caches JIC there's a thread race
            if (cacheKey in notFoundCache) {
                return@withLock ByteApiResponse(
                    404,
                    Headers.Empty,
                    ByteArray(0),
                    "application/octet-stream"
                )
            }
            thumbDiskKache.get(cacheKey)?.let { filePath ->
                return@withLock readBytesResponse(filePath)
            }

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

                    if (result.status == 404) {
                        // 404 case - cache that file doesn't exist in memory only
                        notFoundCache.add(cacheKey)
                        ByteApiResponse(
                            404,
                            Headers.Empty,
                            ByteArray(0),
                            "application/octet-stream"
                        )
                    } else {
                        // 3️⃣ Store to disk
                        thumbDiskKache.put(cacheKey) { filePath ->
                            writeBytesResponse(filePath, result)
                        }
                        result
                    }
                } catch (e: Exception) {
                    // For other errors (500, network issues, etc.), don't cache and rethrow
                    throw e
                }
            }
        } // Mutex.lock
    }


    suspend fun getThumbBytesDecrypted(
        driveId: Uuid,
        fileId: Uuid,
        payloadKey: String,
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

        if (raw.status == 404) return null

        val decryptedBytes = delegate.decryptBytes(raw.headers, raw.bytes)

        return BytesResponse(
            bytes = decryptedBytes,
            contentType = raw.contentType
        )
    }


    // =================================================
    // -------------------- FILE IO --------------------
    // =================================================

    private fun writeBytesResponse(
        filePath: String,
        value: ByteApiResponse
    ): Boolean {
        val path = filePath.toPath()

        fileSystem.write(path) {
            writeInt(value.status)
            writeInt(value.contentType.length)
            writeUtf8(value.contentType)
            write(value.bytes)
            // Note: Headers are not cached to save space and simplify serialization
        }

        return true
    }

    private fun readBytesResponse(
        filePath: String
    ): ByteApiResponse {
        val path = filePath.toPath()

        return fileSystem.read(path) {
            val status = readInt()
            val contentTypeLength = readInt()
            val contentType = readUtf8(contentTypeLength.toLong())
            val bytes = readByteArray()
            ByteApiResponse(status, Headers.Empty, bytes, contentType)
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
        listOf(
            "payload",
            driveId,
            fileId,
            key,
            chunkStart ?: "full",
            chunkLength ?: "full"
        ).joinToString(":")

    private fun buildThumbCacheKey(
        driveId: Uuid,
        fileId: Uuid,
        payloadKey: String,
        width: Int,
        height: Int,
        lastModified: Long?
    ): String =
        listOf(
            "thumb",
            driveId,
            fileId,
            payloadKey,
            width,
            height,
            lastModified ?: "null"
        ).joinToString(":")

    suspend fun clearCaches() {
        payloadDiskKache.clear()
        thumbDiskKache.clear()
        notFoundCache.clear()
    }
}
