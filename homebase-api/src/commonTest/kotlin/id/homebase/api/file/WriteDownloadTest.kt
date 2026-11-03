package id.homebase.api.file

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteDownloadTest {
    private val fs = FakeFileSystem()

    @Test
    fun aDownloadOverTheLimitThrowsAndLeavesNoPartialFile() = runTest {
        val ops = OkioFileOperationsProvider(fs, "/tmp/homebase", downloadLimitBytes = 8)
        assertFailsWith<FileTooLargeForMemoryException> {
            ops.writeDownload("/tmp/homebase/big.bin", flowOf(ByteArray(5), ByteArray(5)))
        }
        assertFalse(fs.exists("/tmp/homebase/big.bin".toPath()))
    }

    @Test
    fun aDownloadUnderTheLimitIsWritten() = runTest {
        val ops = OkioFileOperationsProvider(fs, "/tmp/homebase", downloadLimitBytes = 8)
        ops.writeDownload("/tmp/homebase/ok.bin", flowOf(byteArrayOf(1, 2, 3), byteArrayOf(4)))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), ops.readFileBytes("/tmp/homebase/ok.bin"))
    }

    @Test
    fun withoutALimitItIsWriteStream() = runTest {
        val ops = OkioFileOperationsProvider(fs, "/tmp/homebase")
        ops.writeDownload("/tmp/homebase/free.bin", flowOf(ByteArray(100)))
        assertTrue(ops.getFileSize("/tmp/homebase/free.bin") == 100L)
    }
}
