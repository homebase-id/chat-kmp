package id.homebase.core.ui.screens.openedfile

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenedFileInboxTest {
    private val a = OpenedFileRequest("/stage/a/a.pdf", "a.pdf", "application/pdf", 1)
    private val b = OpenedFileRequest("/stage/b/b.pdf", "b.pdf", "application/pdf", 1)
    private val discarded = mutableListOf<String>()

    @AfterTest
    fun reset() {
        OpenedFileInbox.clear(a.path)
        OpenedFileInbox.clear(b.path)
    }

    @Test
    fun signInToSaveMarksTheFileAsWaiting() {
        OpenedFileInbox.offer(a, discarded::add)
        OpenedFileInbox.waitForSignIn(a)
        assertEquals(PendingOpenedFile(a, waitingForSignIn = true), OpenedFileInbox.pending.value)
    }

    @Test
    fun aSecondFileReplacesOneWaitingForSignInAndDeletesItsCopy() {
        OpenedFileInbox.waitForSignIn(a)
        OpenedFileInbox.offer(b, discarded::add)
        assertEquals(PendingOpenedFile(b), OpenedFileInbox.pending.value)
        assertEquals(listOf(a.path), discarded)
    }

    @Test
    fun aSecondFileLeavesAShownFilesCopyToItsScreen() {
        OpenedFileInbox.offer(a, discarded::add)
        OpenedFileInbox.offer(b, discarded::add)
        assertEquals(PendingOpenedFile(b), OpenedFileInbox.pending.value)
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun clearingAnotherPathKeepsThePendingFile() {
        OpenedFileInbox.offer(a, discarded::add)
        OpenedFileInbox.clear(b.path)
        assertEquals(PendingOpenedFile(a), OpenedFileInbox.pending.value)
        OpenedFileInbox.clear(a.path)
        assertNull(OpenedFileInbox.pending.value)
    }
}
