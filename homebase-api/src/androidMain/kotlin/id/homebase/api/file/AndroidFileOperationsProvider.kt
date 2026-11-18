package id.homebase.api.file

import android.content.Context
import androidx.core.net.toUri
import io.ktor.client.request.forms.InputProvider
import io.ktor.utils.io.streams.asInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okio.FileSystem
import java.io.File

class AndroidFileOperationsProvider(
    val context: Context,
) : OkioFileOperationsProvider(FileSystem.SYSTEM, context.cacheDir.absolutePath) {

    internal fun isContentUri(path: String) = path.startsWith("content:")

    internal fun openContentStream(path: String) =
        context.contentResolver.openInputStream(path.toUri())
            ?: throw IllegalArgumentException("Unable to open content URI: $path")

    override fun openFileInput(path: String): InputProvider =
        if (isContentUri(path)) InputProvider { openContentStream(path).asInput() }
        else super.openFileInput(path)

    override suspend fun readFileBytes(path: String): ByteArray =
        if (isContentUri(path)) withContext(Dispatchers.IO) { openContentStream(path).use { it.readBytes() } }
        else super.readFileBytes(path)

    override fun readFileAsFlow(path: String, chunkSize: Int): Flow<ByteArray> =
        if (isContentUri(path)) {
            flow {
                openContentStream(path).use {
                    // A read returns what one round trip to the provider brought; fill the chunk, as Okio's reader does.
                    while (true) {
                        val buf = ByteArray(chunkSize)
                        var filled = 0
                        while (filled < chunkSize) {
                            val n = it.read(buf, filled, chunkSize - filled)
                            if (n < 0) break
                            filled += n
                        }
                        if (filled > 0) emit(if (filled == chunkSize) buf else buf.copyOf(filled))
                        if (filled < chunkSize) break
                    }
                }
            }.flowOn(Dispatchers.IO)
        } else super.readFileAsFlow(path, chunkSize)

    override suspend fun readFileRange(path: String, offset: Long, length: Int): ByteArray =
        if (isContentUri(path)) openRanges(path).use { it.read(offset, length) } else super.readFileRange(path, offset, length)

    override suspend fun openRanges(path: String): FileRanges {
        if (!isContentUri(path)) return super.openRanges(path)
        val (descriptor, channel) = withContext(Dispatchers.IO) {
            val descriptor = context.contentResolver.openFileDescriptor(path.toUri(), "r")
                ?: throw IllegalArgumentException("Unable to open content URI: $path")
            descriptor to java.io.FileInputStream(descriptor.fileDescriptor).channel
        }
        return object : FileRanges {
            override suspend fun read(offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
                val buffer = java.nio.ByteBuffer.allocate(length)
                var position = offset
                while (buffer.hasRemaining()) {
                    val n = channel.read(buffer, position)
                    if (n <= 0) break
                    position += n
                }
                buffer.array().copyOf(buffer.position())
            }

            override suspend fun size() = withContext(Dispatchers.IO) { channel.size() }

            override fun close() {
                channel.close()
                descriptor.close()
            }
        }
    }

    override suspend fun readFileHeaderBytes(path: String, maxBytes: Int): ByteArray =
        if (isContentUri(path)) {
            withContext(Dispatchers.IO) {
                openContentStream(path).use {
                    val buf = ByteArray(maxBytes)
                    var off = 0
                    while (off < maxBytes) {
                        val n = it.read(buf, off, maxBytes - off)
                        if (n <= 0) break
                        off += n
                    }
                    if (off == maxBytes) buf else buf.copyOf(off)
                }
            }
        } else super.readFileHeaderBytes(path, maxBytes)

    // You generally do NOT own content URIs — never delete them.
    override fun deleteTempFile(path: String): Boolean =
        if (isContentUri(path)) false else super.deleteTempFile(path)

    override fun getFileSize(path: String): Long {
        if (!isContentUri(path)) return super.getFileSize(path)
        context.contentResolver.query(path.toUri(), null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (sizeIndex >= 0 && cursor.moveToFirst()) return cursor.getLong(sizeIndex)
        }
        return 0L
    }

    // getFileSize's SIZE-column query returns 0 for a valid URI that doesn't expose
    // OpenableColumns.SIZE, so opening the stream is the authoritative check; it also
    // surfaces a revoked grant (SecurityException) as "missing".
    override suspend fun sourceExists(path: String): Boolean =
        if (isContentUri(path)) {
            withContext(Dispatchers.IO) {
                runCatching { openContentStream(path).use { true } }.getOrDefault(false)
            }
        } else super.sourceExists(path)

    // Encrypted, ready-to-transmit payloads live in the durable staging dir (#842) — under
    // noBackupFilesDir, NOT cacheDir (OS-reclaimable under storage pressure). Not filesDir:
    // the outbox DB is excluded from backup, so staged files must not be restored without rows.
    override fun getOutboxStagingDirectory(): String =
        File(context.noBackupFilesDir, OUTBOX_STAGING_DIR_NAME).apply { mkdirs() }.absolutePath

    override suspend fun resolveToFilePath(path: String): String {
        if (!isContentUri(path)) return path
        return withContext(Dispatchers.IO) {
            val uri = path.toUri()
            val ext = context.contentResolver.getType(uri)
                ?.substringAfterLast('/')
                ?.let { ".$it" } ?: ""
            val tmp = File.createTempFile("resolved_", ext, File(AppCacheDirs.scratchDir(context.cacheDir.absolutePath, AppCacheDirs.PICKER_COPIES)))
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyInLargeChunks(it) }
            } ?: throw IllegalArgumentException("Unable to open content URI: $path")
            tmp.absolutePath
        }
    }

    override suspend fun writeStream(path: String, data: Flow<ByteArray>) {
        if (!isContentUri(path)) return super.writeStream(path, data)
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(path.toUri())?.use { out ->
                data.collect { chunk -> out.write(chunk) }
            } ?: throw IllegalArgumentException("Unable to open content URI for write: $path")
        }
    }

    override suspend fun overwrite(path: String, offset: Long, bytes: ByteArray) {
        if (!isContentUri(path)) return super.overwrite(path, offset, bytes)
        withContext(Dispatchers.IO) {
            val descriptor = context.contentResolver.openFileDescriptor(path.toUri(), "rw")
                ?: throw IllegalArgumentException("Unable to open content URI for write: $path")
            descriptor.use { fd ->
                java.io.FileOutputStream(fd.fileDescriptor).channel.use { channel ->
                    require(offset >= 0 && offset + bytes.size <= channel.size()) { "overwrite past the end of $path" }
                    val buffer = java.nio.ByteBuffer.wrap(bytes)
                    var at = offset
                    while (buffer.hasRemaining()) at += channel.write(buffer, at)
                }
            }
        }
    }
}
