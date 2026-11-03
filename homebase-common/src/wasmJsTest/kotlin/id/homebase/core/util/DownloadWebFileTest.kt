package id.homebase.core.util

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class DownloadWebFileTest {
    @Test
    fun missingFile_fails() {
        assertTrue(runCatching { downloadWebFile(Path("/no/such/file.pdf"), "file.pdf") }.isFailure)
    }
}
