package id.homebase.core.ui.screens.openedfile

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OpenedFileStagingTest {
    private val fs = FakeFileSystem()
    private val root = "/cache"
    private val staging = "$root/staging"

    @AfterTest
    fun clean() {
        fs.checkNoOpenFiles()
    }

    private fun source(text: String = "hello") = (root.toPath() / "src.bin").also {
        fs.createDirectories(it.parent!!)
        fs.write(it) { writeUtf8(text) }
    }

    @Test
    fun aStagedCopyKeepsTheSanitizedNameSoTheUploadIsNamedRight() {
        val source = source()
        val staged = stageFileCopy(source.toString(), staging, "../../Quarterly Report.pdf", fs)
        assertEquals("Quarterly Report.pdf", staged.toPath().name)
        assertTrue(staged.startsWith("$staging/"), staged)
        assertEquals("hello", fs.read(staged.toPath()) { readUtf8() })
        assertTrue(fs.exists(source), "the original is left alone")
    }

    @Test
    fun twoFilesWithOneNameDoNotShareACopy() {
        val a = reserveStagedPath(staging, "same.txt", fs)
        val b = reserveStagedPath(staging, "same.txt", fs)
        assertNotEquals(a, b)
        assertEquals("same.txt", a.toPath().name)
        assertEquals("same.txt", b.toPath().name)
    }

    @Test
    fun discardingRemovesTheCopyAndItsFolderButNotTheRoot() {
        val staged = stageFileCopy(source("x").toString(), staging, "a.txt", fs)
        discardStaged(staged, fs)
        assertFalse(fs.exists(staged.toPath()))
        assertFalse(fs.exists(staged.toPath().parent!!))
        assertTrue(fs.exists(staging.toPath()))
    }

    @Test
    fun discardingAMissingCopyIsHarmless() {
        discardStaged("$staging/nope/a.txt", fs)
    }

    private fun reservedAt(path: String): Long = fs.metadata(path.toPath().parent!!).lastModifiedAtMillis!!

    @Test
    fun theSweepDeletesStaleCopiesButSparesTheOnesARouteStillShows() {
        val shown = stageFileCopy(source().toString(), staging, "shown.txt", fs)
        val stale = stageFileCopy(source().toString(), staging, "stale.txt", fs)
        sweepStaged(staging, keep = listOf(shown), nowMs = reservedAt(stale) + 2 * 60 * 60 * 1000L, fileSystem = fs)
        assertTrue(fs.exists(shown.toPath()))
        assertFalse(fs.exists(stale.toPath().parent!!))
        assertTrue(fs.exists(staging.toPath()))
    }

    @Test
    fun theSweepSparesACopyThatMayStillBeCopying() {
        val fresh = reserveStagedPath(staging, "fresh.txt", fs)
        sweepStaged(staging, keep = emptyList(), nowMs = reservedAt(fresh) + 1, fileSystem = fs)
        assertTrue(fs.exists(fresh.toPath().parent!!))
    }

    @Test
    fun sweepingAMissingRootIsHarmless() {
        sweepStaged("/nowhere", keep = emptyList(), nowMs = 0, fileSystem = fs)
    }

    @Test
    fun onlyAnotherAppsContentUriIsOpened() {
        val own = setOf("id.homebase.feed.debug.fileprovider")
        fun accepts(scheme: String?, authority: String?) = isForeignContentUri(scheme, authority) { it in own }
        assertTrue(accepts("content", "com.android.providers.downloads.documents"))
        assertFalse(accepts("content", "id.homebase.feed.debug.fileprovider"))
        assertFalse(accepts("content", "0@id.homebase.feed.debug.fileprovider"), "a user prefix still reaches our provider")
        assertFalse(accepts("file", null))
        assertFalse(accepts("file", "localhost"))
        assertFalse(accepts("content", null))
        assertFalse(accepts(null, null))
    }
}
