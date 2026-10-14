package id.homebase.chat.services.calling

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class CallRingTonePlayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir: File = Files.createTempDirectory("ringtone").toFile()

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private val posix = !System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)

    private fun player(script: String): OsRingTonePlayer =
        OsRingTonePlayer(scope, gapMs = 10, source = { RingSource.Command(listOf("sh", "-c", script)) })

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in ${timeoutMs}ms" }
            Thread.sleep(20)
        }
    }

    private fun pids(prefix: String) = dir.listFiles { f -> f.name.startsWith(prefix) }.orEmpty().map { it.readText().trim().toLong() }

    private fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    @Test
    fun stopKillsTheLongRunningPlayerProcessAtOnce() {
        if (!posix) return
        val pidFile = File(dir, "pid-a")
        val tone = player("echo \$\$ > '${pidFile.path}'; exec sleep 60")
        tone.start()
        waitFor { pidFile.exists() && pidFile.readText().isNotBlank() }
        val pid = pidFile.readText().trim().toLong()
        assertTrue(alive(pid))

        tone.stop()
        waitFor(2_000) { !alive(pid) }
    }

    @Test
    fun aNewRingRightAfterAStopNeverOverlapsTheFirstPlayer() {
        if (!posix) return
        val tone = player("echo \$\$ > '${dir.path}'/pid-\$\$; exec sleep 60")
        tone.start()
        waitFor { pids("pid-").size == 1 }
        val first = pids("pid-").single()

        tone.stop()
        tone.start()
        waitFor { pids("pid-").size == 2 }
        waitFor(2_000) { !alive(first) }
        assertEquals(1, pids("pid-").count { alive(it) })

        tone.stop()
        waitFor(2_000) { pids("pid-").none { alive(it) } }
        assertFalse(pids("pid-").any { alive(it) })
    }
}
