package id.homebase.core.util

import id.homebase.core.files.InMemoryFileOps
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A file for the share sheet sits in share_outbound under its name: moved when the caller gives it away, else copied. */
class StageForShareTest {
    private val bytes = "secret".encodeToByteArray()

    @Test
    fun aFileGivenAwayIsMovedUnderItsName() = runTest {
        val fileOps = InMemoryFileOps()
        val source = fileOps.writeBytesToTempFile(bytes, "key", ".asc")
        val staged = fileOps.stageForShare(source, "my key.asc", giveAway = true)
        assertTrue(staged.startsWith("/tmp/share_outbound/"))
        assertEquals("my key.asc", staged.substringAfterLast('/'))
        assertContentEquals(bytes, fileOps.readFileBytes(staged))
        assertFalse(fileOps.sourceExists(source))
    }

    @Test
    fun aFileTheCallerKeepsIsCopied() = runTest {
        val fileOps = InMemoryFileOps()
        val source = fileOps.writeBytesToTempFile(bytes, "photo", ".jpg")
        val staged = fileOps.stageForShare(source, "photo.jpg", giveAway = false)
        assertContentEquals(bytes, fileOps.readFileBytes(staged))
        assertTrue(fileOps.sourceExists(source))
    }

    @Test
    fun aFileThatCantBeMovedIsCopied() = runTest {
        val fileOps = InMemoryFileOps(canMove = false)
        val source = fileOps.writeBytesToTempFile(bytes, "key", ".asc")
        val staged = fileOps.stageForShare(source, "key.asc", giveAway = true)
        assertContentEquals(bytes, fileOps.readFileBytes(staged))
    }

    @Test
    fun aFailedCopyLeavesNothingBehind() = runTest {
        val fileOps = InMemoryFileOps(canMove = false)
        assertFailsWith<IllegalStateException> { fileOps.stageForShare("/tmp/gone.asc", "gone.asc", giveAway = true) }
        assertFalse(fileOps.sourceExists("/tmp/share_outbound/share_0/gone.asc"))
    }
}
