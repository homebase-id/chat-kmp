package id.homebase.api.file

import okio.fakefilesystem.FakeFileSystem

class OkioFakeFsContractTest : FileOperationsContractTest() {
    private val fs = FakeFileSystem()
    private val provider = OkioFileOperationsProvider(fs, "/cache")

    override fun provider(): FileOperationsProvider = provider
    override fun tempDir() = "/contract"
    override fun cleanupTempDir() = fs.checkNoOpenFiles()
}
