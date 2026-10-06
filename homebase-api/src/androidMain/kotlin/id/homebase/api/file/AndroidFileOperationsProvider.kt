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

    private fun isContentUri(path: String) = path.startsWith("content:")

    private fun openContentStream(path: String) =
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
                    val buf = ByteArray(chunkSize)
                    while (true) {
                        val n = it.read(buf, 0, buf.size)
                        if (n <= 0) break
                        emit(buf.copyOf(n))
                    }
                }
            }.flowOn(Dispatchers.IO)
        } else super.readFileAsFlow(path, chunkSize)

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
                tmp.outputStream().use { input.copyTo(it) }
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
}
