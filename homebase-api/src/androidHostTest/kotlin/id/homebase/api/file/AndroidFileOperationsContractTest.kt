package id.homebase.api.file

import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class AndroidFileOperationsContractTest : FileOperationsContractTest() {
    private val dir = Files.createTempDirectory("contract-").toFile()

    override fun provider(): FileOperationsProvider =
        AndroidFileOperationsProvider(ApplicationProvider.getApplicationContext())

    override fun tempDir(): String = dir.absolutePath
    override fun cleanupTempDir() {
        dir.deleteRecursively()
    }
}
