package id.homebase.core.util

import kotlinx.io.files.Path
import java.awt.HeadlessException
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Whatever happens to a save, the caller hears when its file is no longer needed, once, after the outcome. */
class JvmFileSystemHandlerSaveTest {
    private val dir: File = Files.createTempDirectory("save-test").toFile().also { it.deleteOnExit() }

    private fun save(chooseSaveTarget: (String) -> File?, onSuccess: (String) -> Unit = {}): List<String> {
        val src = File(dir, "key.txt").apply { writeText("secret") }
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val settled = CountDownLatch(1)
        JvmFileSystemHandler(JvmAlbumSaver(chooseSaveTarget = chooseSaveTarget, reveal = {})).saveFile(
            Path(src.path),
            "key.txt",
            onSuccess = { calls += "success"; onSuccess(it) },
            onError = { calls += "error" },
            onSettled = { calls += "settled"; settled.countDown() },
        )
        assertTrue(settled.await(5, TimeUnit.SECONDS), "settled")
        Thread.sleep(50)
        return calls.toList()
    }

    @Test
    fun aSaveSettlesAfterItsSuccess() =
        assertEquals(listOf("success", "settled"), save({ File(dir, "out.txt") }))

    @Test
    fun aCancelledSaveStillSettles() =
        assertEquals(listOf("settled"), save({ null }))

    @Test
    fun aFailedSaveSettlesAfterItsError() =
        assertEquals(listOf("error", "settled"), save({ throw HeadlessException() }))

    @Test
    fun aCallbackThatThrowsDoesntStopTheCleanup() =
        assertEquals(listOf("success", "settled"), save({ File(dir, "out2.txt") }, onSuccess = { error("the screen is gone") }))
}
