package id.homebase.core.util

import id.homebase.core.files.InMemoryFileOps
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Whatever happens to a save, the caller hears once, last, that its file is no longer needed, unless it was handed on. */
class SaveThroughAlbumTest {
    private class Run(val calls: List<String>, val thrown: List<Throwable>)

    private fun saver(target: suspend () -> AlbumTarget) = object : AlbumSaver {
        override suspend fun target(name: String, asked: Boolean) = target()
    }

    private suspend fun save(
        saver: AlbumSaver,
        onSuccess: () -> Unit = {},
        onError: () -> Unit = {},
        onFileReleased: () -> Unit = {},
        onRefused: ((Path, String) -> Boolean)? = null,
    ): Run {
        val calls = mutableListOf<String>()
        val thrown = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> thrown += e })
        val refused = onRefused?.let { handOn -> { file: Path, reason: String -> calls += "refused"; handOn(file, reason) } }
        val success = { _: String -> calls += "success"; onSuccess() }
        val error = { _: Throwable -> calls += "error"; onError() }
        val released = { calls += "released"; onFileReleased() }
        if (refused == null) {
            saveThroughAlbum(saver, InMemoryFileOps(), Path("/tmp/key.txt"), "key.txt", success, error, released, scope = scope)
        } else {
            saveThroughAlbum(saver, InMemoryFileOps(), Path("/tmp/key.txt"), "key.txt", success, error, released, refused, scope)
        }
        scope.coroutineContext.job.children.toList().joinAll()
        return Run(calls, thrown)
    }

    private val saved = saver { AlbumTarget.FinishedFile { "Downloads" } }
    private val refusing = saver { AlbumTarget.FinishedFile { throw AlbumAccessDeniedException("no storage access") } }

    @Test
    fun aSaveReleasesAfterItsSuccess() = runTest {
        assertEquals(listOf("success", "released"), save(saved).calls)
    }

    @Test
    fun aCancelledSaveStillReleases() = runTest {
        assertEquals(listOf("released"), save(saver { AlbumTarget.Cancelled }).calls)
    }

    @Test
    fun aFailedSaveReleasesAfterItsError() = runTest {
        assertEquals(listOf("error", "released"), save(saver { error("disk full") }).calls)
    }

    @Test
    fun aThrowableThatIsntAnExceptionIsStillAnErrorThatReleases() = runTest {
        assertEquals(listOf("error", "released"), save(saver { throw NotImplementedError("a JS failure") }).calls)
    }

    @Test
    fun aRefusalReportsAnErrorAndReleasesByDefault() = runTest {
        assertEquals(listOf("error", "released"), save(refusing).calls)
    }

    @Test
    fun aFileHandedOnIsntReleased() = runTest {
        assertEquals(listOf("refused"), save(refusing, onRefused = { _, _ -> true }).calls)
    }

    @Test
    fun aHandOffThatFailsReleases() = runTest {
        assertEquals(listOf("refused", "released"), save(refusing, onRefused = { _, _ -> false }).calls)
    }

    @Test
    fun aCallbackThatThrowsStillReleases() = runTest {
        val run = save(saved, onSuccess = { error("the screen is gone") })
        assertEquals(listOf("success", "released"), run.calls)
        assertEquals("the screen is gone", run.thrown.single().message)
    }

    @Test
    fun aReleaseThatThrowsDoesntHideTheCallbacksFailure() = runTest {
        val run = save(saver { error("disk full") }, onError = { error("the screen is gone") }, onFileReleased = { error("delete failed") })
        assertEquals(listOf("error", "released"), run.calls)
        assertEquals("the screen is gone", run.thrown.single().message)
    }

    @Test
    fun aReleaseThatThrowsIsLoggedNotRaised() = runTest {
        val run = save(saved, onFileReleased = { error("delete failed") })
        assertEquals(listOf("success", "released"), run.calls)
        assertTrue(run.thrown.isEmpty())
    }
}
