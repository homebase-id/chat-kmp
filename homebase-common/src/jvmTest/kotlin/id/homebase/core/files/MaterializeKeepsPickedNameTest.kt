package id.homebase.core.files

import id.homebase.api.file.FileOperationsProvider
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import io.ktor.client.request.forms.InputProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MaterializeKeepsPickedNameTest {

    @Test
    fun theSandboxCopyCarriesTheOriginalPickedName() = runTest {
        val cache = Files.createTempDirectory("cache").toFile().apply { deleteOnExit() }
        val picked = File(Files.createTempDirectory("picked").toFile(), "Quarterly report.pdf").apply {
            writeText("x")
            deleteOnExit()
        }

        val copy = PlatformFile(picked).materializeForUpload(DirOps(cache.absolutePath))

        assertEquals("Quarterly report.pdf", copy.name)
        assertTrue(File(copy.path).exists())
        assertTrue(copy.path.startsWith(cache.absolutePath))
    }

    @Test
    fun twoCopiesOfTheSameNameDoNotCollide() = runTest {
        val cache = Files.createTempDirectory("cache").toFile().apply { deleteOnExit() }
        val picked = File(Files.createTempDirectory("picked").toFile(), "a.png").apply {
            writeText("x")
            deleteOnExit()
        }
        val ops = DirOps(cache.absolutePath)

        val a = PlatformFile(picked).materializeForUpload(ops)
        val b = PlatformFile(picked).materializeForUpload(ops)

        assertEquals(a.name, b.name)
        assertNotEquals(a.path, b.path)
    }
}

private class DirOps(private val cache: String) : FileOperationsProvider {
    override fun getCacheDirectory(): String = cache
    override fun openFileInput(path: String): InputProvider = throw NotImplementedError()
    override suspend fun readFileBytes(path: String): ByteArray = throw NotImplementedError()
    override fun deleteTempFile(path: String): Boolean = throw NotImplementedError()
    override fun getFileSize(path: String): Long = throw NotImplementedError()
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String =
        throw NotImplementedError()
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String =
        throw NotImplementedError()
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) = throw NotImplementedError()
}
