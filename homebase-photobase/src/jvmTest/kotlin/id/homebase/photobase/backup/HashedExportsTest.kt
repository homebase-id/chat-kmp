package id.homebase.photobase.backup

import id.homebase.api.file.FileOperationsProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HashedExportsTest {
    private val sizes = mutableMapOf<String, Long>()
    private val deleted = mutableListOf<String>()
    private var exportSize = 10L

    private val files = object : FileOperationsProvider by NoFiles {
        override fun getFileSize(path: String) = sizes[path] ?: 0L
        override fun deleteTempFile(path: String): Boolean = deleted.add(path)
        override fun readFileAsFlow(path: String, chunkSize: Int): Flow<ByteArray> = flowOf(path.encodeToByteArray())
    }

    private inner class CountingSource : DeviceChangeSource by FakeSource() {
        var exports = 0

        override suspend fun export(assetId: String, mediaType: BackupMediaType): ExportedAsset {
            exports++
            val path = "$assetId-$exports"
            sizes[path] = exportSize
            return ExportedAsset(listOf(ExportedPart(ExportedPartRole.Primary, path, "image/jpeg", isTemporary = true)), assetId)
        }
    }

    private val source = CountingSource()
    private val exports = HashedExports(source, files)
    private val hasher = AssetHasher(exports, files)

    @Test
    fun aHashedPhotoIsNotExportedAgain() = runTest {
        hasher.hash("a", BackupMediaType.Image)

        val exported = exports.export("a", BackupMediaType.Image)

        assertEquals(1, source.exports)
        assertEquals("a-1", exported.parts.single().path)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun aKeptExportIsHandedOutOnlyOnce() = runTest {
        hasher.hash("a", BackupMediaType.Image)
        exports.export("a", BackupMediaType.Image)

        exports.export("a", BackupMediaType.Image)

        assertEquals(2, source.exports)
    }

    @Test
    fun aVideoIsDeletedAfterHashingAndExportedAgain() = runTest {
        hasher.hash("a", BackupMediaType.Video)
        assertEquals(listOf("a-1"), deleted)

        exports.export("a", BackupMediaType.Video)

        assertEquals(2, source.exports)
    }

    @Test
    fun whatDoesNotFitIsDeletedAndWhatFitsStays() = runTest {
        exportSize = 200L * 1024 * 1024
        hasher.hash("a", BackupMediaType.Image)
        hasher.hash("b", BackupMediaType.Image)
        assertEquals(listOf("b-2"), deleted)

        assertEquals("a-1", exports.export("a", BackupMediaType.Image).parts.single().path)
        hasher.hash("c", BackupMediaType.Image)
        assertEquals(listOf("b-2"), deleted)
    }

    @Test
    fun aKeptFileThatWasSweptIsExportedAgain() = runTest {
        hasher.hash("a", BackupMediaType.Image)
        sizes.remove("a-1")

        val exported = exports.export("a", BackupMediaType.Image)

        assertEquals("a-2", exported.parts.single().path)
    }

    @Test
    fun forgettingDeletesTheKeptFiles() = runTest {
        hasher.hash("a", BackupMediaType.Image)
        hasher.hash("b", BackupMediaType.Image)

        hasher.forget("a")
        assertEquals(listOf("a-1"), deleted)

        hasher.forgetAll()
        assertEquals(listOf("a-1", "b-2"), deleted)
    }

    @Test
    fun timingsCountRealAndReusedExportsApart() = runTest {
        val timings = BackupTimings()

        withContext(timings) {
            hasher.hash("a", BackupMediaType.Image)
            exports.export("a", BackupMediaType.Image)
        }

        val summary = timings.summary()
        assertContains(summary, "Hash=")
        assertTrue(Regex("""Export=\d+ms/1""").containsMatchIn(summary), summary)
        assertTrue(Regex("""ExportReused=\d+ms/1""").containsMatchIn(summary), summary)
    }
}
