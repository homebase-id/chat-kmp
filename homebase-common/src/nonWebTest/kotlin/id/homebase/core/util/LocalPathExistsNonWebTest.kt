package id.homebase.core.util

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString

class LocalPathExistsNonWebTest {
    @Test
    fun writtenFile_isTrue() {
        val path = Path(SystemTemporaryDirectory, "local_path_exists_test.txt")
        SystemFileSystem.sink(path).buffered().use { it.writeString("x") }
        try {
            assertTrue(localPathExists(path.toString()))
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }
}
