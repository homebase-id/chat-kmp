package id.homebase.api.client.drives.cache

import com.mayakapps.kache.FileKache
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.BytesResponse
import id.homebase.api.client.drives.files.DriveFileProvider
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.uuid.Uuid
import okio.buffer
import okio.use


class DriveFileProviderCached(
    private val delegate: DriveFileProvider
) {

    private val fileSystem = FileSystem.SYSTEM

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

    suspend fun getFileHeader(
        driveId: Uuid,
        fileId: Uuid
    ): HomebaseFile? =
        delegate.getFileHeader(driveId, fileId)

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

        // 1️⃣ Try cache
        payloadCache.get(cacheKey)?.let { filePath ->
            return readBytesResponse(filePath)
        }

        // 2️⃣ Fetch from network
        val result =
            delegate.getPayloadBytesDecrypted(
                driveId,
                fileId,
                key,
                chunkStart,
                chunkLength
            ) ?: return null

        // 3️⃣ Store to disk
        payloadCache.put(cacheKey) { filePath ->
            writeBytesResponse(filePath, result)
        }

        return result
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
            val bytes = readByteArray()
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
