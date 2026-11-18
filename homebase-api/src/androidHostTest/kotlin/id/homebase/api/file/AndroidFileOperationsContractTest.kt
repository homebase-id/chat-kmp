package id.homebase.api.file

import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidFileOperationsContractTest : FileOperationsContractTest() {
    private val dir = Files.createTempDirectory("contract-").toFile()

    override fun provider(): FileOperationsProvider =
        AndroidFileOperationsProvider(ApplicationProvider.getApplicationContext())

    override fun tempDir(): String = dir.absolutePath
    override fun cleanupTempDir() {
        dir.deleteRecursively()
    }
}
