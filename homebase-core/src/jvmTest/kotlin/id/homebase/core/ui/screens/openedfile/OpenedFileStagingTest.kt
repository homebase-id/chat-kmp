package id.homebase.core.ui.screens.openedfile

import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OpenedFileStagingTest {
    private val fs = FileSystem.SYSTEM
    private val root = Files.createTempDirectory("open-with").toString()

    @AfterTest
    fun clean() {
        fs.deleteRecursively(root.toPath())
    }

    @Test
    fun aStagedCopyKeepsTheSanitizedNameSoTheUploadIsNamedRight() {
        val source = (root.toPath() / "src.bin").also { fs.write(it) { writeUtf8("hello") } }
        val staged = stageFileCopy(source.toString(), "$root/staging", "../../Quarterly Report.pdf", fs)
        assertEquals("Quarterly Report.pdf", staged.toPath().name)
        assertTrue(staged.startsWith("$root/staging/"), staged)
        assertEquals("hello", fs.read(staged.toPath()) { readUtf8() })
        assertTrue(fs.exists(source), "the original is left alone")
    }

    @Test
    fun twoFilesWithOneNameDoNotShareACopy() {
        val a = reserveStagedPath("$root/staging", "same.txt", fs)
        val b = reserveStagedPath("$root/staging", "same.txt", fs)
        assertNotEquals(a, b)
        assertEquals("same.txt", a.toPath().name)
        assertEquals("same.txt", b.toPath().name)
    }

    @Test
    fun discardingRemovesTheCopyAndItsFolderButNotTheRoot() {
        val source = (root.toPath() / "src.bin").also { fs.write(it) { writeUtf8("x") } }
        val staged = stageFileCopy(source.toString(), "$root/staging", "a.txt", fs)
        discardStaged(staged, fs)
        assertFalse(fs.exists(staged.toPath()))
        assertFalse(fs.exists(staged.toPath().parent!!))
        assertTrue(fs.exists("$root/staging".toPath()))
    }

    @Test
    fun discardingAMissingCopyIsHarmless() {
        discardStaged("$root/staging/nope/a.txt", fs)
    }
}
