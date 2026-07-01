package id.homebase.api.file

import android.content.Context
import androidx.core.net.toUri
import io.ktor.client.request.forms.InputProvider
import io.ktor.utils.io.streams.asInput
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.io.FileOutputStream

class AndroidFileOperationsProvider(
    val context: Context,
) : FileOperationsProvider {
    override fun openFileInput(path: String): InputProvider {
        return InputProvider {
            if (path.startsWith("content://") || path.startsWith("content:")) {
                val uri = path.toUri()
                context.contentResolver.openInputStream(uri)?.asInput()
                    ?: throw IllegalArgumentException("Unable to open content URI: $path")
            } else {
                File(path).inputStream().asInput()
            }
        }
    }

    override suspend fun readFileBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        if (path.startsWith("content://") || path.startsWith("content:")) {
            val uri = path.toUri()
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("Unable to read content URI: $path")
        } else {
            File(path).readBytes()
        }
    }

    override fun readFileAsFlow(path: String, chunkSize: Int): kotlinx.coroutines.flow.Flow<ByteArray> =
        kotlinx.coroutines.flow.flow {
            val stream = if (path.startsWith("content://") || path.startsWith("content:")) {
                context.contentResolver.openInputStream(path.toUri())
                    ?: throw IllegalArgumentException("Unable to open content URI: $path")
            } else {
                File(path).inputStream()
            }
            stream.use {
                val buf = ByteArray(chunkSize)
                while (true) {
                    val n = it.read(buf, 0, buf.size)
                    if (n <= 0) break
                    emit(buf.copyOf(n))
                }
            }
        }

    override suspend fun readFileHeaderBytes(path: String, maxBytes: Int): ByteArray =
        withContext(Dispatchers.IO) {
            val stream = if (path.startsWith("content://") || path.startsWith("content:")) {
                context.contentResolver.openInputStream(path.toUri())
                    ?: throw IllegalArgumentException("Unable to open content URI: $path")
            } else {
                File(path).inputStream()
            }
            stream.use {
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

    override fun deleteTempFile(path: String): Boolean {
        return runCatching {
            if (path.startsWith("content://") || path.startsWith("content:")) {
                // You generally do NOT own content URIs — never delete them
                false
            } else {
                val file = File(path)

                if (!file.exists() || file.isDirectory) {
                    true
                } else if (file.delete()) {
                    true
                } else {
                    // No deleteOnExit on Android — JVM exit is not reliable
                    false
                }
            }
        }.getOrDefault(false)
    }

    override fun getCacheDirectory(): String = context.cacheDir.absolutePath

    override fun getFileSize(path: String): Long {
        if (path.startsWith("content://") || path.startsWith("content:")) {
            val uri = path.toUri()

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)

                if (sizeIndex >= 0 && cursor.moveToFirst()) {
                    return cursor.getLong(sizeIndex)
                }
            }

            return 0L
        }

        return File(path).length()
    }

    override suspend fun sourceExists(path: String): Boolean = withContext(Dispatchers.IO) {
        if (path.startsWith("content://") || path.startsWith("content:")) {
            // getFileSize's SIZE-column query returns 0 for a perfectly valid URI that
            // doesn't expose OpenableColumns.SIZE, so don't gate on size here. Opening
            // the stream is the authoritative "still readable?" check and also surfaces
            // a revoked grant (SecurityException) as "missing".
            runCatching {
                context.contentResolver.openInputStream(path.toUri())?.use { true } ?: false
            }.getOrDefault(false)
        } else {
            File(path).exists()
        }
    }

    override suspend fun writeBytesToTempFile(
        bytes: ByteArray, prefix: String, suffix: String
    ): String = writeBytesIn(CacheAudit.UPLOAD_TEMP_DIR_NAME, bytes, prefix, suffix)

    // Encrypted, ready-to-transmit payloads → outbox-temp/ (KEEP-protected; #844 PR4).
    override suspend fun writeBytesToOutboxTempFile(
        bytes: ByteArray, prefix: String, suffix: String
    ): String = writeBytesIn(CacheAudit.OUTBOX_TEMP_DIR_NAME, bytes, prefix, suffix)

    private suspend fun writeBytesIn(
        dirName: String, bytes: ByteArray, prefix: String, suffix: String
    ): String = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, dirName).apply { mkdirs() }
        val file = File.createTempFile(prefix, suffix, tempDir)
        file.writeBytes(bytes)
        file.path
    }

    override suspend fun writeBytesToShareOutboundFile(
        bytes: ByteArray, suffix: String
    ): String = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, SHARE_OUTBOUND_DIR_NAME).apply { mkdirs() }
        val file = File.createTempFile("share_", suffix, dir)
        file.writeBytes(bytes)
        file.path
    }

    override suspend fun resolveToFilePath(path: String): String {
        if (!path.startsWith("content://") && !path.startsWith("content:")) return path
        return withContext(Dispatchers.IO) {
            val uri = path.toUri()
            val ext = context.contentResolver.getType(uri)
                ?.substringAfterLast('/')
                ?.let { ".$it" } ?: ""
            val tmp = File.createTempFile("resolved_", ext, context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalArgumentException("Unable to open content URI: $path")
            tmp.absolutePath
        }
    }

    override suspend fun writeStream(
        path: String,
        data: Flow<ByteArray>
    ) = withContext(Dispatchers.IO) {

        if (path.startsWith("content://") || path.startsWith("content:")) {

            val uri = path.toUri()

            context.contentResolver.openOutputStream(uri)?.use { out ->
                data.collect { chunk ->
                    out.write(chunk)
                }
            } ?: throw IllegalArgumentException("Unable to open content URI for write: $path")

        } else {

            FileOutputStream(File(path)).use { out ->
                data.collect { chunk ->
                    out.write(chunk)
                }
            }

        }
    }
}
