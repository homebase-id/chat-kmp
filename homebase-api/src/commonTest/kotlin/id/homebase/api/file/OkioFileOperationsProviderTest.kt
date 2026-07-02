package id.homebase.api.file

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Regression guard for the file-operations layer the web upload/attachment pipeline runs on.
 *
 * The web ([WebFileOperationsProvider]) is exactly [OkioFileOperationsProvider] bound to the wasm
 * [systemFileSystem], which is an in-memory okio `FakeFileSystem`. These tests exercise the same
 * provider over a `FakeFileSystem` — so they cover the real web behavior (write/read/size/stream/
 * delete) while running in the normal JVM test job (no wasm harness needed). If the web file ops
 * regress, sending media on web breaks with "file not found"; this catches that.
 */
class OkioFileOperationsProviderTest {

    private fun provider() = OkioFileOperationsProvider(FakeFileSystem(), "/tmp/homebase")

    @Test
    fun writeTempRoundTripsThroughReadSizeHeaderOpenAndDelete() = runTest {
        val ops = provider()
        val bytes = byteArrayOf(10, 20, 30, 40)

        val path = ops.writeBytesToTempFile(bytes, "guard_", ".bin")
        assertTrue(path.isNotBlank(), "temp path must be returned")
        assertTrue(path.endsWith(".bin"), "suffix preserved: $path")

        assertEquals(4L, ops.getFileSize(path), "getFileSize must match written bytes")
        assertContentEquals(bytes, ops.readFileBytes(path), "readFileBytes must round-trip")
        assertContentEquals(bytes, ops.readFileHeaderBytes(path, 64), "header read must return the bytes")
        assertEquals(4L, ops.openFileInput(path).size, "openFileInput must report the size")

        assertTrue(ops.deleteTempFile(path), "delete must succeed")
        assertEquals(0L, ops.getFileSize(path), "size must be 0 after delete")
        assertTrue(ops.deleteTempFile(path), "deleting a missing file is a no-op success")
    }

    @Test
    fun readFileAsFlowEmitsRealChunksThatConcatenateToTheFile() = runTest {
        val ops = provider()
        // Non-repeating-ish content larger than two 4 KB chunks so ordering bugs show.
        val bytes = ByteArray(10_000) { (it % 251).toByte() }
        val path = ops.writeBytesToTempFile(bytes, "flow_", ".bin")

        val chunks = ops.readFileAsFlow(path, chunkSize = 4096).toList()

        assertTrue(chunks.size >= 3, "must stream in real chunks, not one whole-file emit (got ${chunks.size})")
        assertTrue(chunks.all { it.size <= 4096 }, "no chunk may exceed the requested size")
        val concatenated = ByteArray(bytes.size)
        var off = 0
        for (c in chunks) {
            c.copyInto(concatenated, off)
            off += c.size
        }
        assertEquals(bytes.size, off, "chunks must cover the full file")
        assertContentEquals(bytes, concatenated, "chunks must concatenate to the original bytes")
    }

    @Test
    fun writeStreamConcatenatesChunksInOrder() = runTest {
        val ops = provider()
        val path = "${ops.getCacheDirectory()}/streamed.bin"

        ops.writeStream(path, flowOf(byteArrayOf(1, 2), byteArrayOf(3), byteArrayOf(4, 5)))

        assertContentEquals(
            byteArrayOf(1, 2, 3, 4, 5),
            ops.readFileBytes(path),
            "streamed chunks must concatenate in order",
        )
    }

    @Test
    fun shareOutboundWritesUnderItsSubdir() = runTest {
        val ops = provider()
        val path = ops.writeBytesToShareOutboundFile(byteArrayOf(7, 8, 9), ".dat")

        assertTrue(path.contains(SHARE_OUTBOUND_DIR_NAME), "must live under the share-outbound subdir: $path")
        assertContentEquals(byteArrayOf(7, 8, 9), ops.readFileBytes(path), "share file must round-trip")
    }

    /**
     * #947: openFileInput must be LAZY and CHUNKED — the block reopens the file per
     * invocation (ktor re-invokes it on retries; the outbox re-drives whole sends),
     * and nothing is read until the block runs.
     */
    @Test
    fun openFileInputStreamsLazilyAndIsReinvokable() = runTest {
        val ops = provider()
        // Bigger than one 64 KB chunk so the chunk loop actually iterates.
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        val path = ops.writeBytesToTempFile(bytes, "input_", ".bin")

        val input = ops.openFileInput(path)
        assertEquals(bytes.size.toLong(), input.size, "size must come from metadata")

        // Two invocations of the SAME provider block must each yield the full bytes —
        // the old impl replayed one eagerly-captured array; the contract is reopen-per-call.
        repeat(2) { round ->
            val source = input.block()
            assertContentEquals(bytes, source.readByteArray(), "block invocation #$round must stream the full file")
        }
    }

    @Test
    fun openFileInputReadsNothingUntilTheBlockRuns() = runTest {
        val ops = provider()
        val path = ops.writeBytesToTempFile(byteArrayOf(1, 2, 3), "lazy_", ".bin")

        val input = ops.openFileInput(path)
        // Deleting AFTER openFileInput but BEFORE the block runs must make the read
        // fail — proof no eager read happened at openFileInput time.
        ops.deleteTempFile(path)

        assertFails("a missing file must surface when the block runs, not earlier") {
            input.block().readByteArray()
        }
    }

    /**
     * #845: the streaming seams for export flows — reserve a unique path in an
     * EXISTING swept dir without writing bytes.
     */
    @Test
    fun createShareOutboundPathReservesUniquePathsUnderShareOutbound() = runTest {
        val ops = provider()

        val a = ops.createShareOutboundPath(".zip")
        val b = ops.createShareOutboundPath(".zip")

        assertTrue(a.contains(SHARE_OUTBOUND_DIR_NAME), "must live in the sequestered share dir: $a")
        assertTrue(a.endsWith(".zip") && a != b, "paths must be unique, suffixed reservations")
        assertEquals(0L, ops.getFileSize(a), "reservation must not write bytes")
        ops.writeStream(a, flowOf(byteArrayOf(1, 2)))
        assertContentEquals(byteArrayOf(1, 2), ops.readFileBytes(a))
    }

    @Test
    fun createUploadTempPathReservesUnderUploadTemp() = runTest {
        val ops = provider()

        val path = ops.createUploadTempPath("share_", ".pdf")

        assertTrue(path.contains(CacheAudit.UPLOAD_TEMP_DIR_NAME), "must live in upload-temp/: $path")
        assertTrue(path.substringAfterLast('/').startsWith("share_") && path.endsWith(".pdf"))
        assertEquals(0L, ops.getFileSize(path), "reservation must not write bytes")
    }

    @Test
    fun getFileSizeOfMissingFileIsZero() {
        assertEquals(0L, provider().getFileSize("/tmp/homebase/does-not-exist.bin"))
    }

    @Test
    fun sourceExistsTrueForWrittenFileFalseAfterDelete() = runTest {
        val ops = provider()
        val path = ops.writeBytesToTempFile(byteArrayOf(1, 2, 3), "src_", ".bin")

        assertTrue(ops.sourceExists(path), "a written source must report present")

        ops.deleteTempFile(path)
        assertTrue(!ops.sourceExists(path), "a swept source must report missing (fail-soft signal)")
    }

    @Test
    fun sourceExistsTrueForZeroByteFile() = runTest {
        val ops = provider()
        // A real (if empty) file must not be misreported as missing — sourceExists is an
        // existence probe, not a size probe (unlike getFileSize, which returns 0 for both).
        val path = ops.writeBytesToTempFile(ByteArray(0), "empty_", ".bin")

        assertEquals(0L, ops.getFileSize(path), "the file is genuinely zero-length")
        assertTrue(ops.sourceExists(path), "a present zero-byte file must still report present")
    }

    @Test
    fun sourceExistsFalseForMissingPath() = runTest {
        assertTrue(!provider().sourceExists("/tmp/homebase/never-written.bin"))
    }
}
