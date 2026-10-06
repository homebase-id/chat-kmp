package id.homebase.api.file

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

@OptIn(ExperimentalForeignApi::class)
class IosFileOperationsContractTest : FileOperationsContractTest() {
    private val dir = NSTemporaryDirectory() + "contract-" + NSUUID().UUIDString

    override fun provider(): FileOperationsProvider = IOSFileOperationsProvider()
    override fun tempDir(): String = dir
    override fun cleanupTempDir() {
        NSFileManager.defaultManager.removeItemAtPath(dir, null)
    }
}
