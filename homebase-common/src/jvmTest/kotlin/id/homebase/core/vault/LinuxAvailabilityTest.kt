package id.homebase.core.vault

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinuxAvailabilityTest {

    @Test
    fun `finds an executable in a later PATH entry`() {
        val empty = Files.createTempDirectory("path-empty").toFile()
        val bin = Files.createTempDirectory("path-bin").toFile()
        val tool = File(bin, "pkcheck").apply { writeText(""); setExecutable(true) }
        try {
            val path = listOf(empty.path, "", bin.path).joinToString(File.pathSeparator)
            assertEquals(tool, findOnPath("pkcheck", path))
        } finally {
            bin.deleteRecursively()
            empty.deleteRecursively()
        }
    }

    @Test
    fun `ignores a non-executable match`() {
        val bin = Files.createTempDirectory("path-bin").toFile()
        File(bin, "pkcheck").apply { writeText(""); setExecutable(false) }
        try {
            assertNull(findOnPath("pkcheck", bin.path))
        } finally {
            bin.deleteRecursively()
        }
    }

    @Test
    fun `null or empty PATH finds nothing`() {
        assertNull(findOnPath("pkcheck", null))
        assertNull(findOnPath("pkcheck", ""))
    }
}
