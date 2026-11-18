package id.homebase.core.util

import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Desktop's real saveFile: the Save dialog's outcome, then the caller's file deleted when it gave it away. */
class JvmFileSystemHandlerSaveTest {
    private val dir: File = Files.createTempDirectory("save-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun handler(chooseSaveTarget: (String) -> File?) =
        JvmFileSystemHandler(JvmAlbumSaver(chooseSaveTarget = chooseSaveTarget, reveal = {}))

    private fun awaitGone(file: File) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (file.exists() && System.nanoTime() < deadline) Thread.onSpinWait()
        assertTrue(!file.exists(), "${file.name} deleted")
    }

    @Test
    fun aSavedFileIsCopiedThenDeleted() {
        val key = File(dir, "key.txt").apply { writeText("secret") }
        val target = File(dir, "out.txt")
        handler { target }.saveFile(Path(key.path), "key.txt", deleteAfter = true)
        awaitGone(key)
        assertEquals("secret", target.readText())
    }

    @Test
    fun aCancelledSaveDialogStillDeletes() {
        val key = File(dir, "key.txt").apply { writeText("secret") }
        handler { null }.saveFile(Path(key.path), "key.txt", deleteAfter = true)
        awaitGone(key)
    }
}
