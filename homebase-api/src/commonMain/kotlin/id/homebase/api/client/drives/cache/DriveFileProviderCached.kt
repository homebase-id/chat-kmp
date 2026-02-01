package id.homebase.api.client.drives.cache

import com.mayakapps.kache.FileKache
import id.homebase.api.client.ByteApiResponse
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadOperationOptions
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
import okio.buffer
import okio.use


class DriveFileProviderCached(
    private val delegate: DriveFileProvider)
{

    private val fileSystem = FileSystem.SYSTEM

    private val payloadSemaphore = Semaphore(3)
    private val thumbnailSemaphore = Semaphore(30)
    private val keyLocks = mutableMapOf<String, Mutex>()
    private val lock = Mutex()
    
    // In-memory cache for 404 responses
    // Later we should cache 401,403,404,410, but not yet
    private val notFoundCache = mutableSetOf<String>()

    private val payloadCache by lazy {
        kotlinx.coroutines.runBlocking {
            FileKache(
                directory = "homebase-drive-cache",
                maxSize = 512L * 1024L * 1024L
            )
        }
    }
    // -------------------- PASSTHROUGH --------------------

    suspend fun cachePayloadStreaming(
        cache: FileKache,
        key: String,
        input: okio.Source
    ): Boolean {

        val fileSystem = FileSystem.SYSTEM

        return cache.put(key) { filePath ->
            val path = filePath.toPath()

            fileSystem.sink(path).buffer().use { sink ->
                input.use { source ->
                    sink.writeAll(source)
                }
            }

            true // tell FileKache creation succeeded
        } != null
    }

    // -------------------- CACHED METHODS --------------------

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
        payloadCache.get(cacheKey)?.let { filePath ->
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
                return@withLock ByteApiResponse(404, Headers.Empty, ByteArray(0), "application/octet-stream")
            }
            payloadCache.get(cacheKey)?.let { filePath ->
                return@withLock readBytesResponse(filePath)
            }

            // we allow up to 3 concurrent semaphore payloads over the network
            return payloadSemaphore.withPermit {
                try {
                    val result =
                        delegate.getPayloadBytesRaw(
                            driveId,
                            fileId,
                            key,
                            options
                        )

                    if (result.status == 404) {
                        // 404 case - cache that file doesn't exist in memory only
                        notFoundCache.add(cacheKey)
                        ByteApiResponse(404, Headers.Empty, ByteArray(0), "application/octet-stream")
                    } else {
                        // 3️⃣ Store to disk
                        payloadCache.put(cacheKey) { filePath ->
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



    // -------------------- FILE IO --------------------

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

    // -------------------- CACHE KEYS --------------------

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
}
