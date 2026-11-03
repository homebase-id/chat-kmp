package id.homebase.core.logging

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogFilesMissingDirContractTest {

    private val missing = Path("/no/such/dir/homebase-logs")

    @Test
    fun listingAMissingDirectoryIsEmpty() {
        assertEquals(emptyList(), listHomebaseLogFiles(missing))
    }

    @Test
    fun deletingFromAMissingDirectoryReportsNothingLeft() {
        assertTrue(deleteHomebaseLogFiles(missing))
    }
}
