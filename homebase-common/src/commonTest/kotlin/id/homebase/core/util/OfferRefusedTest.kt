package id.homebase.core.util

import id.homebase.core.files.InMemoryFileOps
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** A refused save is offered once, without the album, and the refused copy is deleted after, whatever happens. */
class OfferRefusedTest {
    private suspend fun offer(onRefused: suspend (String, String, String) -> Unit): Pair<InMemoryFileOps, String> {
        val fileOps = InMemoryFileOps()
        val file = fileOps.writeBytesToTempFile("photo".encodeToByteArray(), "scratch", ".jpg")
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        offerRefusedThrough(fileOps, AlbumSaveResult.Refused(file, "photo.jpg", "no storage access"), onRefused, scope)
        scope.coroutineContext.job.children.toList().joinAll()
        return fileOps to file
    }

    @Test
    fun theRefusedFileIsOfferedUnderItsNameThenDeleted() = runTest {
        var offered: Pair<String, String>? = null
        val (fileOps, file) = offer { f, name, _ -> offered = f to name }
        assertEquals(file to "photo.jpg", offered)
        assertFalse(fileOps.sourceExists(file))
    }

    @Test
    fun theRefusedFileIsDeletedEvenWhenOfferingItFails() = runTest {
        val (fileOps, file) = offer { _, _, _ -> error("no share sheet") }
        assertFalse(fileOps.sourceExists(file))
    }

    @Test
    fun withNothingElseToTakeItTheRefusalIsReportedAsAnError() = runTest {
        var reported: Throwable? = null
        val (fileOps, file) = offer(refusalError { reported = it })
        assertIs<AlbumAccessDeniedException>(reported)
        assertEquals("no storage access", reported?.message)
        assertFalse(fileOps.sourceExists(file))
    }
}
