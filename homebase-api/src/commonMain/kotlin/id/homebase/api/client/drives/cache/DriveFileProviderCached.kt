package id.homebase.api.client.drives.cache

import com.mayakapps.kache.FileKache
import id.homebase.api.client.drives.files.BytesResponse
import id.homebase.api.client.drives.files.DriveFileProvider
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

// Special marker for non-existent files
private const val NON_EXISTENT_FILE_MARKER = "__NON_EXISTENT__"


class DriveFileProviderCached(
    private val delegate: DriveFileProvider)
{

    private val fileSystem = FileSystem.SYSTEM

    private val payloadSemaphore = Semaphore(3)
    private val thumbnailSemaphore = Semaphore(30)
    private val keyLocks = mutableMapOf<String, Mutex>()
    private val lock = Mutex()

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

    // NOT to be cached - if you want cached, get it from the database...
    //    suspend fun getFileHeader(
    //        driveId: Uuid,
    //        fileId: Uuid
    //    ): HomebaseFile? =
    //        delegate.getFileHeader(driveId, fileId)

    // -------------------- CACHED METHODS --------------------

    suspend fun getPayloadBytesDecrypted(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        chunkStart: Long? = null,
        chunkLength: Long? = null
    ): BytesResponse? {

        val cacheKey =
            buildPayloadCacheKey(
                driveId,
                fileId,
                key,
                chunkStart,
                chunkLength
            )

        // 1️⃣ Peek in cache and return if it's there
        payloadCache.get(cacheKey)?.let { filePath ->
            val result = readBytesResponse(filePath)
            // Return null if we previously cached this as non-existent
            if (result.contentType == NON_EXISTENT_FILE_MARKER) {
                return null
            }
            return result
        }

        // 2️⃣ Fetch from network but lock to make sure that we don't load the same
        // resource twice over the network
        val mutex: Mutex
        lock.withLock {
            mutex = keyLocks.getOrPut(cacheKey) { Mutex() }
        }

        return mutex.withLock {
            // Re-try cache JIC there's a thread race
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
                            chunkStart,
                            chunkLength
                        )

                    if (result != null) {
                        // 3️⃣ Store to disk
                        payloadCache.put(cacheKey) { filePath ->
                            writeBytesResponse(filePath, result)
                        }
                        result
                    } else {
                        // 404 case - cache that file doesn't exist
                        cacheFileNonExistent(cacheKey)
                        null
                    }
                } catch (e: Exception) {
                    // For other errors (500, network issues, etc.), don't cache and rethrow
                    throw e
                }
            }
        } // Mutex.lock
    }

    // -------------------- CACHE NON-EXISTENT FILES --------------------

    private suspend fun cacheFileNonExistent(cacheKey: String) {
        payloadCache.put(cacheKey) { filePath ->
            val path = filePath.toPath()
            fileSystem.write(path) {
                writeInt(NON_EXISTENT_FILE_MARKER.length)
                writeUtf8(NON_EXISTENT_FILE_MARKER)
                // No bytes for non-existent files
            }
            true
        }
    }

    // -------------------- FILE IO --------------------

    private fun writeBytesResponse(
        filePath: String,
        value: BytesResponse
    ): Boolean {
        val path = filePath.toPath()

        fileSystem.write(path) {
            writeInt(value.contentType.length)
            writeUtf8(value.contentType)
            write(value.bytes)
        }

        return true
    }

    private fun readBytesResponse(
        filePath: String
    ): BytesResponse {
        val path = filePath.toPath()

        return fileSystem.read(path) {
            val contentTypeLength = readInt()
            val contentType = readUtf8(contentTypeLength.toLong())
            val bytes = if (contentType == NON_EXISTENT_FILE_MARKER) {
                ByteArray(0)
            } else {
                readByteArray()
            }
            BytesResponse(bytes, contentType)
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
