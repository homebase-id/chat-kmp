package id.homebase.api.file

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

abstract class FileOperationsContractTest {
    abstract fun provider(): FileOperationsProvider
    abstract fun tempDir(): String
    abstract fun cleanupTempDir()

    private val created = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        created.forEach { provider().deleteTempFile(it) }
        cleanupTempDir()
    }

    private fun path(name: String) = "${tempDir()}/$name"
    private fun bytes(s: String) = s.encodeToByteArray()

    @Test
    fun writeStreamReplacesExistingFile() = runTest {
        val p = provider()
        val path = path("replace.bin")
        p.writeStream(path, flowOf(bytes("AAAA")))
        p.writeStream(path, flowOf(bytes("BB")))
        assertEquals("BB", p.readFileBytes(path).decodeToString())
    }

    @Test
    fun writeStreamEmptyFlowYieldsEmptyExistingFile() = runTest {
        val p = provider()
        val path = path("empty.bin")
        p.writeStream(path, flowOf(bytes("AAAA")))
        p.writeStream(path, emptyFlow())
        assertEquals(0L, p.getFileSize(path))
        assertTrue(p.sourceExists(path))
    }

    @Test
    fun writeStreamCreatesMissingParentDirectories() = runTest {
        val p = provider()
        val path = path("a/b/c/nested.bin")
        p.writeStream(path, flowOf(bytes("x")))
        assertEquals("x", p.readFileBytes(path).decodeToString())
    }

    @Test
    fun writeStreamConcatenatesChunksInOrder() = runTest {
        val p = provider()
        val path = path("chunks.bin")
        p.writeStream(path, flowOf(byteArrayOf(1, 2), byteArrayOf(3), byteArrayOf(4, 5)))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), p.readFileBytes(path))
    }

    @Test
    fun readFileAsFlowStreamsRealChunksThatRoundTrip() = runTest {
        val p = provider()
        val path = path("big.bin")
        val data = ByteArray(10_000) { (it % 251).toByte() }
        p.writeStream(path, flowOf(data))
        val chunks = p.readFileAsFlow(path, chunkSize = 4096).toList()
        assertTrue(chunks.size >= 3, "got ${chunks.size} chunks")
        assertTrue(chunks.all { it.size <= 4096 })
        assertContentEquals(data, chunks.fold(ByteArray(0)) { acc, c -> acc + c })
    }

    @Test
    fun readFileHeaderBytesReturnsAtMostMaxBytes() = runTest {
        val p = provider()
        val path = path("header.bin")
        val data = ByteArray(1000) { (it % 251).toByte() }
        p.writeStream(path, flowOf(data))
        assertContentEquals(data.copyOf(100), p.readFileHeaderBytes(path, 100))
        assertContentEquals(data, p.readFileHeaderBytes(path, 5000))
    }

    @Test
    fun sizeAndExistenceForWrittenEmptyAndMissingFiles() = runTest {
        val p = provider()
        val written = path("sized.bin")
        val empty = path("zero.bin")
        val missing = path("missing.bin")
        p.writeStream(written, flowOf(bytes("12345")))
        p.writeStream(empty, emptyFlow())

        assertEquals(5L, p.getFileSize(written))
        assertTrue(p.sourceExists(written))
        assertEquals(0L, p.getFileSize(empty))
        assertTrue(p.sourceExists(empty))
        assertEquals(0L, p.getFileSize(missing))
        assertFalse(p.sourceExists(missing))
    }

    @Test
    fun deleteTempFileRemovesFileAndTreatsMissingPathAsSuccess() = runTest {
        val p = provider()
        val path = path("del.bin")
        p.writeStream(path, flowOf(bytes("x")))
        assertTrue(p.deleteTempFile(path))
        assertFalse(p.sourceExists(path))
        assertTrue(p.deleteTempFile(path))
        assertTrue(p.deleteTempFile(path("never-existed.bin")))
    }

    @Test
    fun writeBytesToTempFileLandsUniqueUnderUploadTemp() = runTest {
        val p = provider()
        val a = p.writeBytesToTempFile(bytes("one"), "pre_", ".bin").also { created += it }
        val b = p.writeBytesToTempFile(bytes("two"), "pre_", ".bin").also { created += it }
        val dir = AppCacheDirs.scratchPath(p.getCacheDirectory(), CacheAudit.UPLOAD_TEMP_DIR_NAME)
        assertNotEquals(a, b)
        for (f in listOf(a, b)) {
            assertTrue(f.startsWith("$dir/pre_"), f)
            assertTrue(f.endsWith(".bin"), f)
        }
        assertEquals("one", p.readFileBytes(a).decodeToString())
    }

    @Test
    fun writeBytesToShareOutboundFileLandsUniqueUnderShareOutbound() = runTest {
        val p = provider()
        val a = p.writeBytesToShareOutboundFile(bytes("one"), ".txt").also { created += it }
        val b = p.writeBytesToShareOutboundFile(bytes("two"), ".txt").also { created += it }
        val dir = AppCacheDirs.scratchPath(p.getCacheDirectory(), SHARE_OUTBOUND_DIR_NAME)
        assertNotEquals(a, b)
        for (f in listOf(a, b)) {
            assertTrue(f.startsWith("$dir/share_"), f)
            assertTrue(f.endsWith(".txt"), f)
        }
        assertEquals("two", p.readFileBytes(b).decodeToString())
    }

    @Test
    fun reservedPathsAreUniqueWellFormedAndNotCreated() = runTest {
        val p = provider()
        val cacheDir = p.getCacheDirectory()
        val uploadDir = AppCacheDirs.scratchPath(cacheDir, CacheAudit.UPLOAD_TEMP_DIR_NAME)
        val shareDir = AppCacheDirs.scratchPath(cacheDir, SHARE_OUTBOUND_DIR_NAME)
        val stagingDir = p.getOutboxStagingDirectory()

        val upload = listOf(p.createUploadTempPath("up_", ".bin"), p.createUploadTempPath("up_", ".bin"))
        val share = listOf(p.createShareOutboundPath(".txt"), p.createShareOutboundPath(".txt"))
        val staging = listOf(p.createOutboxStagingPath("st_", ".enc"), p.createOutboxStagingPath("st_", ".enc"))

        for ((paths, dir, prefix, suffix) in listOf(
            Reserved(upload, uploadDir, "up_", ".bin"),
            Reserved(share, shareDir, "share_", ".txt"),
            Reserved(staging, stagingDir, "st_", ".enc"),
        )) {
            assertNotEquals(paths[0], paths[1])
            for (f in paths) {
                assertTrue(f.startsWith("${dir.trimEnd('/')}/$prefix"), f)
                assertTrue(f.endsWith(suffix), f)
                assertFalse(p.sourceExists(f), "reservation must not create $f")
            }
        }
    }

    private data class Reserved(val paths: List<String>, val dir: String, val prefix: String, val suffix: String)
}
