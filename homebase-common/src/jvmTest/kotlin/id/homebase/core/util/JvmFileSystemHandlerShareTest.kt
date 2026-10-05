package id.homebase.core.util

import kotlinx.io.files.Path
import org.junit.Assume.assumeFalse
import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmFileSystemHandlerShareTest {
    private val dir: File = Files.createTempDirectory("share-test").toFile().also { it.deleteOnExit() }

    @Test
    fun shareFileSavesCopyDetachedFromCaller() {
        val src = File(dir, "homebase.log").apply { writeText("log-body") }
        val target = File(dir, "out.log")
        val revealed = CountDownLatch(1)
        val revealedFile = AtomicReference<File>()
        val returned = AtomicBoolean(false)
        val onEdt = AtomicBoolean(false)
        val detached = AtomicBoolean(false)
        val name = AtomicReference<String>()

        val handler = JvmFileSystemHandler(
            chooseSaveTarget = {
                onEdt.set(SwingUtilities.isEventDispatchThread())
                detached.set(returned.get())
                name.set(it)
                target
            },
            reveal = { revealedFile.set(it); revealed.countDown() },
        )
        handler.shareFile(Path(src.path))
        returned.set(true)

        assertTrue(revealed.await(5, TimeUnit.SECONDS))
        assertEquals("log-body", target.readText())
        assertEquals(target, revealedFile.get())
        assertTrue(onEdt.get())
        assertTrue(detached.get())
        assertEquals("homebase.log", name.get())
    }

    @Test
    fun shareFileReportsPickerFailure() {
        val src = File(dir, "a.log").apply { writeText("x") }
        val failed = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val handler = JvmFileSystemHandler(
            chooseSaveTarget = { throw HeadlessException() },
            reveal = {},
        )
        handler.shareFile(Path(src.path), onError = { error.set(it); failed.countDown() })

        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertTrue(error.get() is HeadlessException)
    }

    @Test
    fun shareTextCopiesToClipboard() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        JvmFileSystemHandler().shareText("x")
        val text = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor)
        assertEquals("x", text)
    }
}
