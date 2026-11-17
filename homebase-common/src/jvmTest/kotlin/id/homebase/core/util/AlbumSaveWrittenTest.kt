package id.homebase.core.util

import id.homebase.api.file.OkioFileOperationsProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import okio.FileSystem

/** [saveWritten], the one way every platform saves what it produces: in place, or through a scratch file. */
class AlbumSaveWrittenTest {
    private val dir: File = Files.createTempDirectory("album-save").toFile().also { it.deleteOnExit() }
    private val fileOps = OkioFileOperationsProvider(FileSystem.SYSTEM, dir.absolutePath)
    private val scratch = File(dir, "scratch.mp4")
    private val events = mutableListOf<String>()

    private fun saver(target: AlbumTarget) = object : AlbumSaver {
        override fun save(file: Path, suggestedName: String, onSuccess: (String) -> Unit, onError: (Throwable) -> Unit) = error("unused")
        override suspend fun target(name: String, asked: Boolean) = target
    }

    private fun inPlace(file: File) = AlbumTarget.InPlace(
        path = file.absolutePath,
        publish = { events += "publish"; "Movies/Homebase" },
        discard = { events += "discard"; file.delete() },
    )

    @Test
    fun inPlaceIsWrittenAtItsOwnPathThenPublished() = runTest {
        val dest = File(dir, "album.mp4")
        val result = saver(inPlace(dest)).saveWritten("v.mp4", asked = true, scratch.absolutePath, fileOps) { path ->
            File(path).writeText("video"); true
        }
        assertEquals(AlbumSaveResult.Saved("Movies/Homebase"), result)
        assertEquals("video", dest.readText())
        assertEquals(listOf("publish"), events)
        assertFalse(scratch.exists(), "no scratch file in place")
    }

    @Test
    fun aFailedOrThrowingWriteDiscardsTheInPlaceItem() = runTest {
        val dest = File(dir, "half.mp4")
        val failed = saver(inPlace(dest)).saveWritten("v.mp4", asked = true, scratch.absolutePath, fileOps) { path ->
            File(path).writeText("half"); false
        }
        assertEquals(AlbumSaveResult.WriteFailed, failed)
        assertFalse(dest.exists())
        assertFailsWith<IOException> {
            saver(inPlace(dest)).saveWritten("v.mp4", asked = true, scratch.absolutePath, fileOps) { path ->
                File(path).writeText("half"); throw IOException("connection reset")
            }
        }
        assertFalse(dest.exists())
        assertEquals(listOf("discard", "discard"), events)
    }

    @Test
    fun aFinishedFileGoesThroughScratchWhichIsDeletedAfter() = runTest {
        var taken = ""
        val target = AlbumTarget.FinishedFile { file -> taken = File(file).readText(); "Photos" }
        val result = saver(target).saveWritten("v.mp4", asked = true, scratch.absolutePath, fileOps) { path ->
            assertEquals(scratch.absolutePath, path)
            File(path).writeText("video"); true
        }
        assertEquals(AlbumSaveResult.Saved("Photos"), result)
        assertEquals("video", taken)
        assertFalse(scratch.exists())

        val failed = saver(target).saveWritten("v.mp4", asked = true, scratch.absolutePath, fileOps) { path ->
            File(path).writeText("half"); false
        }
        assertEquals(AlbumSaveResult.WriteFailed, failed)
        assertFalse(scratch.exists())
    }

    @Test
    fun cancelledWritesNothing() = runTest {
        var wrote = false
        val result = saver(AlbumTarget.Cancelled).saveWritten("v.mp4", asked = true, scratch.absolutePath, fileOps) { wrote = true; true }
        assertEquals(AlbumSaveResult.Cancelled, result)
        assertFalse(wrote)
    }

    @Test
    fun desktopAskedWritesTheFileTheSaveDialogChoseAndRevealsIt() = runTest {
        val chosen = File(dir, "chosen.mp4")
        var revealed: File? = null
        val saver = JvmAlbumSaver(chooseSaveTarget = { chosen }, reveal = { revealed = it })
        val result = saver.saveWritten("v.mp4", asked = true, scratch.absolutePath, fileOps) { path ->
            File(path).writeText("video"); true
        }
        assertEquals(AlbumSaveResult.Saved(chosen.absolutePath), result)
        assertEquals("video", chosen.readText())
        assertEquals(chosen, revealed)
        assertFalse(scratch.exists())

        val cancelled = JvmAlbumSaver(chooseSaveTarget = { null }).saveWritten("v.mp4", asked = true, scratch.absolutePath, fileOps) { true }
        assertEquals(AlbumSaveResult.Cancelled, cancelled)
    }

    @Test
    fun desktopUnaskedGoesToPicturesWithoutADialog() = runTest {
        val target = JvmAlbumSaver(chooseSaveTarget = { error("no dialog for an automatic save") }).target("auto-save-probe.mp4", asked = false)
        target as AlbumTarget.InPlace
        val file = File(target.path)
        assertEquals(File(System.getProperty("user.home"), "Pictures"), file.parentFile)
        assertTrue(file.name.startsWith("auto-save-probe"))
        assertFalse(file.exists(), "nothing is created until it's written")
        target.discard()
    }
}
