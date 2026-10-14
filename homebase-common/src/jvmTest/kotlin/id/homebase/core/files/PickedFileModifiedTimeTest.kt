package id.homebase.core.files

import io.github.vinceglb.filekit.PlatformFile
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PickedFileModifiedTimeTest {

    @Test
    fun aDesktopFileKeepsItsOwnModificationTime() {
        val file = Files.createTempFile("picked", ".pdf").toFile().apply { deleteOnExit() }
        val march2016 = 1_457_000_000_000L
        file.setLastModified(march2016)

        assertEquals(march2016, PlatformFile(file).modifiedAtMs())
    }

    @Test
    fun aMissingFileHasNoModificationTime() {
        assertNull(PlatformFile(File("/no/such/file.txt")).modifiedAtMs())
    }
}
