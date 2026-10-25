package id.homebase.api.file

import java.nio.file.Files

class JvmFileOperationsContractTest : FileOperationsContractTest() {
    private val dir = Files.createTempDirectory("contract-").toFile()

    override fun provider(): FileOperationsProvider = JvmFileOperationsProvider()
    override fun tempDir(): String = dir.absolutePath
    override fun cleanupTempDir() {
        dir.deleteRecursively()
    }
}
