package id.homebase.core.util

import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.common.TestTempDirs
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.FileSystem

/** [saveFile] and [saveWritten], the one way every platform saves: in place, or handed over as a finished file. */
class AlbumSaveWrittenTest {
    private val dir: File = TestTempDirs.dir("album-save").toFile().also { it.deleteOnExit() }
    private val fileOps = OkioFileOperationsProvider(FileSystem.SYSTEM, dir.absolutePath)
    private val scratch = File(dir, "scratch.mp4")
    private val events = mutableListOf<String>()

    private fun saver(target: AlbumTarget) = object : AlbumSaver {
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
        val result = saver(inPlace(dest)).saveWritten("v.mp4", scratch.absolutePath, fileOps) { path ->
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
        val failed = saver(inPlace(dest)).saveWritten("v.mp4", scratch.absolutePath, fileOps) { path ->
            File(path).writeText("half"); false
        }
        assertEquals(AlbumSaveResult.WriteFailed, failed)
        assertFalse(dest.exists())
        assertFailsWith<IOException> {
            saver(inPlace(dest)).saveWritten("v.mp4", scratch.absolutePath, fileOps) { path ->
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
        val result = saver(target).saveWritten("v.mp4", scratch.absolutePath, fileOps) { path ->
            assertEquals(scratch.absolutePath, path)
            File(path).writeText("video"); true
        }
        assertEquals(AlbumSaveResult.Saved("Photos"), result)
        assertEquals("video", taken)
        assertFalse(scratch.exists())

        val failed = saver(target).saveWritten("v.mp4", scratch.absolutePath, fileOps) { path ->
            File(path).writeText("half"); false
        }
        assertEquals(AlbumSaveResult.WriteFailed, failed)
        assertFalse(scratch.exists())
    }

    @Test
    fun cancelledWritesNothing() = runTest {
        var wrote = false
        val result = saver(AlbumTarget.Cancelled).saveWritten("v.mp4", scratch.absolutePath, fileOps) { wrote = true; true }
        assertEquals(AlbumSaveResult.Cancelled, result)
        assertFalse(wrote)
    }

    @Test
    fun desktopAskedWritesTheFileTheSaveDialogChoseAndRevealsIt() = runTest {
        val chosen = File(dir, "chosen.mp4")
        var revealed: File? = null
        val saver = JvmAlbumSaver(chooseSaveTarget = { chosen }, reveal = { revealed = it })
        val result = saver.saveWritten("v.mp4", scratch.absolutePath, fileOps) { path ->
            File(path).writeText("video"); true
        }
        assertEquals(AlbumSaveResult.Saved(chosen.absolutePath), result)
        assertEquals("video", chosen.readText())
        assertFalse(File(dir, ".chosen.mp4.part").exists())
        assertEquals(chosen, revealed)
        assertFalse(scratch.exists())

        val cancelled = JvmAlbumSaver(chooseSaveTarget = { null }).saveWritten("v.mp4", scratch.absolutePath, fileOps) { true }
        assertEquals(AlbumSaveResult.Cancelled, cancelled)
    }

    @Test
    fun desktopReplacingAFileKeepsItWhenTheSaveFails() = runTest {
        val existing = File(dir, "clip.mp4").apply { writeText("the person's own video") }
        val saver = JvmAlbumSaver(chooseSaveTarget = { existing }, reveal = {})
        assertFailsWith<IOException> {
            saver.saveWritten("clip.mp4", scratch.absolutePath, fileOps) { path ->
                assertFalse(path == existing.absolutePath, "written beside it, not over it")
                File(path).writeText("half"); throw IOException("connection reset")
            }
        }
        assertEquals("the person's own video", existing.readText())
        assertEquals(listOf("clip.mp4"), dir.list()!!.filter { it.contains("clip") }, "and no partial file left")

        val replaced = saver.saveWritten("clip.mp4", scratch.absolutePath, fileOps) { path -> File(path).writeText("new video"); true }
        assertEquals(AlbumSaveResult.Saved(existing.absolutePath), replaced)
        assertEquals("new video", existing.readText())
    }

    @Test
    fun aRefusedSaveHandsTheFinishedFileBack() = runTest {
        val target = AlbumTarget.FinishedFile { throw AlbumAccessDeniedException("WRITE_EXTERNAL_STORAGE not granted") }
        val result = saver(target).saveWritten("v.mp4", scratch.absolutePath, fileOps) { path -> File(path).writeText("video"); true }
        assertEquals(AlbumSaveResult.Refused(scratch.absolutePath, "v.mp4", "WRITE_EXTERNAL_STORAGE not granted"), result)
        assertEquals("video", scratch.readText(), "kept for the share sheet")
    }

    @Test
    fun aFinishedFileIsCopiedInPlaceAndPublished() = runTest {
        val source = File(dir, "photo.jpg").apply { writeText("photo") }
        val dest = File(dir, "album.jpg")
        assertEquals(AlbumSaveResult.Saved("Movies/Homebase"), saver(inPlace(dest)).saveFile(source.absolutePath, "photo.jpg", asked = true, fileOps))
        assertEquals("photo", dest.readText())
        assertTrue(source.exists(), "the caller's file is copied, not moved")
        assertEquals(listOf("publish"), events)
    }

    @Test
    fun aCopyThatFailsLeavesNothing() = runTest {
        val dest = File(dir, "album.jpg")
        assertFailsWith<Exception> { saver(inPlace(dest)).saveFile(File(dir, "gone.jpg").absolutePath, "gone.jpg", asked = true, fileOps) }
        assertFalse(dest.exists())
        assertEquals(listOf("discard"), events)
    }

    @Test
    fun aRefusalHandsTheFileBackWithItsReason() = runTest {
        val source = File(dir, "photo.jpg").apply { writeText("photo") }
        val refusing = object : AlbumSaver {
            override suspend fun target(name: String, asked: Boolean): AlbumTarget = throw AlbumAccessDeniedException("WRITE_EXTERNAL_STORAGE not granted")
        }
        assertEquals(
            AlbumSaveResult.Refused(source.absolutePath, "photo.jpg", "WRITE_EXTERNAL_STORAGE not granted"),
            refusing.saveFile(source.absolutePath, "photo.jpg", asked = true, fileOps),
        )
        // What saveWritten produced is handed back the same way, finished, for the caller to share.
        val written = refusing.saveWritten("v.mp4", scratch.absolutePath, fileOps) { path -> File(path).writeText("video"); true }
        assertEquals(AlbumSaveResult.Refused(scratch.absolutePath, "v.mp4", "WRITE_EXTERNAL_STORAGE not granted"), written)
        assertEquals("video", scratch.readText())
    }

    @Test
    fun desktopsAutomaticSaveGoesToPicturesWithoutADialog() = runTest {
        val home = File(dir, "home").apply { mkdirs() }
        val realHome = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
        try {
            val source = File(dir, "auto.jpg").apply { writeText("photo") }
            val saver = JvmAlbumSaver(chooseSaveTarget = { error("no dialog for an automatic save") }, reveal = { error("nor a reveal") })
            assertEquals(AlbumSaveResult.Saved("Pictures"), saver.saveFile(source.absolutePath, "auto.jpg", asked = false, fileOps))
            assertEquals(listOf("auto.jpg"), File(home, "Pictures").list()!!.toList(), "no partial file left")
            assertEquals("photo", File(home, "Pictures/auto.jpg").readText())
            saver.saveFile(source.absolutePath, "notes.pdf", asked = false, fileOps)
            assertTrue(File(home, "Downloads/notes.pdf").exists(), "anything not a photo or video goes to Downloads")
        } finally {
            System.setProperty("user.home", realHome)
        }
    }
}
