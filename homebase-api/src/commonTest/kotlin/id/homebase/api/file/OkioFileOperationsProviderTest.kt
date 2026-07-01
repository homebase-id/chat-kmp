package id.homebase.api.file

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
