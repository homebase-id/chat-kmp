package id.homebase.core.util

import id.homebase.core.files.InMemoryFileOps
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A save the caller gives its file to deletes it once done, whatever the outcome; any other leaves it alone. */
class FileSaverTest {
    private class Run(val calls: List<String>, val thrown: List<Throwable>, val fileLeft: Boolean)

    private class JsLikeFailure(message: String) : Throwable(message)

    private fun saver(target: suspend () -> AlbumTarget) = object : AlbumSaver {
        override suspend fun target(name: String, asked: Boolean) = target()
    }

    private suspend fun TestScope.save(
        saver: AlbumSaver,
        deleteAfter: Boolean = true,
        onSuccess: () -> Unit = {},
        onError: () -> Unit = {},
        onRefused: (suspend (file: String, name: String, giveAway: Boolean) -> Unit)? = null,
        cancelBeforeItRuns: Boolean = false,
    ): Run {
        val fileOps = InMemoryFileOps()
        val file = fileOps.writeBytesToTempFile("secret".encodeToByteArray(), "key", ".txt")
        val calls = mutableListOf<String>()
        val thrown = mutableListOf<Throwable>()
        val dispatcher = if (cancelBeforeItRuns) StandardTestDispatcher(testScheduler) else EmptyCoroutineContext
        val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> thrown += e })
        val success = { _: String -> calls += "success"; onSuccess() }
        val error = { _: Throwable -> calls += "error"; onError() }
        val fallback = onRefused?.let { handOn ->
            RefusedSaveFallback { refused, giveAway, _ -> calls += "refused"; handOn(refused.file, refused.name, giveAway) }
        } ?: ReportRefusal
        FileSaver(saver, fileOps, fallback, scope).save(Path(file), "key.txt", deleteAfter, success, error)
        val launched = scope.coroutineContext.job.children.toList()
        if (cancelBeforeItRuns) scope.cancel()
        launched.joinAll()
        return Run(calls, thrown, fileOps.sourceExists(file))
    }

    private val saved = saver { AlbumTarget.FinishedFile { "Downloads" } }
    private val refusing = saver { AlbumTarget.FinishedFile { throw AlbumAccessDeniedException("no storage access") } }

    @Test
    fun aSavedFileIsDeletedAfterTheSuccess() = runTest {
        val run = save(saved)
        assertEquals(listOf("success"), run.calls)
        assertFalse(run.fileLeft)
    }

    @Test
    fun aFileTheCallerKeepsIsNeverDeleted() = runTest {
        assertTrue(save(saved, deleteAfter = false).fileLeft)
        assertTrue(save(saver { AlbumTarget.Cancelled }, deleteAfter = false).fileLeft)
        assertTrue(save(saver { error("disk full") }, deleteAfter = false).fileLeft)
    }

    @Test
    fun aCancelledSaveStillDeletes() = runTest {
        val run = save(saver { AlbumTarget.Cancelled })
        assertEquals(emptyList(), run.calls)
        assertFalse(run.fileLeft)
    }

    @Test
    fun aFailedSaveReportsAndDeletes() = runTest {
        val run = save(saver { error("disk full") })
        assertEquals(listOf("error"), run.calls)
        assertFalse(run.fileLeft)
    }

    @Test
    fun aFailureThatIsntAnExceptionIsStillReportedAndDeletes() = runTest {
        val run = save(saver { throw JsLikeFailure("a JS failure") })
        assertEquals(listOf("error"), run.calls)
        assertFalse(run.fileLeft)
    }

    @Test
    fun anErrorIsReportedThenRethrownAndStillDeletes() = runTest {
        val run = save(saver { throw NotImplementedError("a bug") })
        assertEquals(listOf("error"), run.calls)
        assertTrue(run.thrown.single() is NotImplementedError)
        assertFalse(run.fileLeft)
    }

    @Test
    fun aRefusalReportsAnErrorAndDeletesByDefault() = runTest {
        val run = save(refusing)
        assertEquals(listOf("error"), run.calls)
        assertFalse(run.fileLeft)
    }

    @Test
    fun aRefusalHandlerGetsTheFileAndItsSafeNameAndTheFileIsStillDeleted() = runTest {
        var handed: Pair<String, String>? = null
        val run = save(refusing, onRefused = { file, name, _ -> handed = file to name })
        assertEquals(listOf("refused"), run.calls)
        val file = Path(handed!!.first)
        assertEquals(Path("/tmp"), file.parent)
        assertTrue(file.name.startsWith("key"), "$file")
        assertEquals("key.txt", handed!!.second)
        assertFalse(run.fileLeft)
    }

    @Test
    fun aRefusalHandlerThatThrowsIsReportedAndStillDeletes() = runTest {
        val run = save(refusing, onRefused = { _, _, _ -> error("no share sheet") })
        assertEquals(listOf("refused", "error"), run.calls)
        assertTrue(run.thrown.isEmpty())
        assertFalse(run.fileLeft)
    }

    @Test
    fun aRefusalOfAFileTheCallerKeepsLeavesIt() = runTest {
        assertTrue(save(refusing, deleteAfter = false, onRefused = { _, _, _ -> }).fileLeft)
    }

    @Test
    fun aCallbackThatThrowsStillDeletes() = runTest {
        val run = save(saved, onSuccess = { error("the screen is gone") })
        assertEquals("the screen is gone", run.thrown.single().message)
        assertFalse(run.fileLeft)
    }

    @Test
    fun aSaveCancelledBeforeItRunsStillDeletes() = runTest {
        assertFalse(save(saved, cancelBeforeItRuns = true).fileLeft)
    }

    @Test
    fun theFallbackMayGiveTheFileAwayOnlyWhenTheCallerDeletesIt() = runTest {
        var giveAway: Boolean? = null
        save(refusing, onRefused = { _, _, give -> giveAway = give })
        assertEquals(true, giveAway)
        save(refusing, deleteAfter = false, onRefused = { _, _, give -> giveAway = give })
        assertEquals(false, giveAway)
    }

    private class Offered(val fileOps: InMemoryFileOps, val file: String, val errors: List<Throwable>)

    private suspend fun offer(fallbackOn: (InMemoryFileOps) -> RefusedSaveFallback = { ReportRefusal }): Offered {
        val fileOps = InMemoryFileOps()
        val fallback = fallbackOn(fileOps)
        val file = fileOps.writeBytesToTempFile("photo".encodeToByteArray(), "scratch", ".jpg")
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        FileSaver(refusing, fileOps, fallback, scope).offerRefused(AlbumSaveResult.Refused(file, "photo.jpg", "denied")) { errors += it }
        scope.coroutineContext.job.children.toList().joinAll()
        return Offered(fileOps, file, errors)
    }

    @Test
    fun aRefusedFileIsOfferedOnceUnderItsNameToGiveAwayThenDeleted() = runTest {
        val offered = mutableListOf<Triple<String, String, Boolean>>()
        val run = offer { RefusedSaveFallback { refused, giveAway, _ -> offered += Triple(refused.file, refused.name, giveAway) } }
        assertEquals(listOf(Triple(run.file, "photo.jpg", true)), offered)
        assertFalse(run.fileOps.sourceExists(run.file))
    }

    @Test
    fun withNothingElseToTakeItARefusedFileIsReportedAndDeleted() = runTest {
        val run = offer()
        assertIs<AlbumAccessDeniedException>(run.errors.single())
        assertEquals("denied", run.errors.single().message)
        assertFalse(run.fileOps.sourceExists(run.file))
    }

    @Test
    fun aFallbackThatFailsIsReportedAndTheFileStillDeleted() = runTest {
        val run = offer { RefusedSaveFallback { _, _, _ -> error("no share sheet") } }
        assertEquals("no share sheet", run.errors.single().message)
        assertFalse(run.fileOps.sourceExists(run.file))
    }

    @Test
    fun theShareSheetGetsTheFileStagedUnderItsNameOnTheMainDispatcher() = runTest {
        val shared = mutableListOf<String>()
        val run = offer { ops -> ShareSheetFallback(ops, StandardTestDispatcher(testScheduler)) { path, _ -> shared += path.toString() } }
        assertEquals(1, shared.size)
        val staged = Path(shared.single())
        assertEquals("photo.jpg", staged.name)
        assertTrue(generateSequence(staged.parent) { it.parent }.any { it == Path("/tmp/share_outbound") }, "$staged")
        assertTrue(run.fileOps.sourceExists(shared.single()), "the staged copy stays for the share target")
        assertFalse(run.fileOps.sourceExists(run.file), "the refused file was given away")
        assertTrue(run.errors.isEmpty())
    }
}
