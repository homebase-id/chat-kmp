package id.homebase.chat.conversationlist

import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the contract the web `PlatformFile.toUploadPath` actual relies on: a picked file's
 * bytes, once materialized, must be READABLE BACK through the same [FileOperationsProvider] the
 * send pipeline uses. The web Gallery/image send bug was exactly this contract being skipped —
 * the raw browser PlatformFile was never copied into okio, so `readFileBytes` couldn't find it.
 *
 * (The web `readBytes()` interop itself can't run without a browser; this covers the shared
 * copy-and-readability logic, which is the part that was missing.)
 */
class AttachmentUploadResolveTest {

    @Test
    fun materializedPathIsReadableBackWithSameProvider() = runTest {
        val fs = InMemoryFileOps()
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7)

        val path = materializeBytesToUploadPath(fs, "photo.JPG", bytes)

        // The upload pipeline reads attachment paths via this provider — it must succeed.
        assertEquals(bytes.toList(), fs.readFileBytes(path).toList())
        // Extension is preserved so downstream content-type / muxer logic still works.
        assertTrue(path.endsWith(".JPG"), "expected .JPG suffix, got: $path")
    }

    @Test
    fun materializeHandlesNameWithoutExtension() = runTest {
        val fs = InMemoryFileOps()
        val bytes = byteArrayOf(42)

        val path = materializeBytesToUploadPath(fs, "noextension", bytes)

        assertEquals(bytes.toList(), fs.readFileBytes(path).toList())
        assertTrue(!path.endsWith("."), "no trailing dot when name has no extension: $path")
    }

    @Test
    fun eachMaterializeGetsADistinctPath() = runTest {
        val fs = InMemoryFileOps()
        val a = materializeBytesToUploadPath(fs, "a.png", byteArrayOf(1))
        val b = materializeBytesToUploadPath(fs, "a.png", byteArrayOf(2))
        assertTrue(a != b, "two attachments must not collide on one temp path")
        assertEquals(listOf<Byte>(1), fs.readFileBytes(a).toList())
        assertEquals(listOf<Byte>(2), fs.readFileBytes(b).toList())
    }
}

/** Minimal in-memory [FileOperationsProvider] — only the members the materialize path touches. */
private class InMemoryFileOps : FileOperationsProvider {
    private val store = mutableMapOf<String, ByteArray>()
    private var counter = 0

    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String {
        val path = "/tmp/$prefix${counter++}$suffix"
        store[path] = bytes
        return path
    }

    override suspend fun readFileBytes(path: String): ByteArray =
        store[path] ?: error("file not found: $path")

    override fun getFileSize(path: String): Long = (store[path]?.size ?: 0).toLong()
    override fun deleteTempFile(path: String): Boolean = store.remove(path) != null
    override fun getCacheDirectory(): String = "/tmp"

    override fun openFileInput(path: String): InputProvider = throw NotImplementedError()
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String =
        throw NotImplementedError()
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) = throw NotImplementedError()
}
