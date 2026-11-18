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
import kotlin.test.assertTrue

/** A save the caller gives its file to deletes it once done, whatever the outcome; any other leaves it alone. */
class SaveThroughAlbumTest {
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
        onRefused: (suspend (String, String, String) -> Unit)? = null,
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
        if (onRefused == null) {
            saveThroughAlbum(saver, fileOps, Path(file), "key.txt", deleteAfter, success, error, scope = scope)
        } else {
            val refused: suspend (String, String, String) -> Unit =
                { f, name, reason -> calls += "refused"; onRefused(f, name, reason) }
            saveThroughAlbum(saver, fileOps, Path(file), "key.txt", deleteAfter, success, error, refused, scope)
        }
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
        assertTrue(handed!!.first.startsWith("/tmp/key"))
        assertEquals("key.txt", handed!!.second)
        assertFalse(run.fileLeft)
    }

    @Test
    fun aRefusalHandlerThatThrowsStillDeletes() = runTest {
        val run = save(refusing, onRefused = { _, _, _ -> error("no share sheet") })
        assertEquals("no share sheet", run.thrown.single().message)
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
}
