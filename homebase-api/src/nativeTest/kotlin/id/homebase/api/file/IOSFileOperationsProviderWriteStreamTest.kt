package id.homebase.api.file

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalForeignApi::class)
class IOSFileOperationsProviderWriteStreamTest {
    private val dir = NSTemporaryDirectory() + "writestream-1802-" + NSUUID().UUIDString
    private val path = "$dir/out.bin"
    private val provider = IOSFileOperationsProvider()

    @AfterTest
    fun cleanup() {
        NSFileManager.defaultManager.removeItemAtPath(dir, null)
    }

    @Test
    fun secondWriteReplacesFirst() = runTest {
        provider.writeStream(path, flowOf("AAAA".encodeToByteArray()))
        provider.writeStream(path, flowOf("BB".encodeToByteArray()))
        assertEquals("BB", provider.readFileBytes(path).decodeToString())
    }

    @Test
    fun chunksInOneCallConcatenate() = runTest {
        provider.writeStream(path, flowOf("ab".encodeToByteArray(), "cd".encodeToByteArray()))
        assertEquals("abcd", provider.readFileBytes(path).decodeToString())
    }
}
