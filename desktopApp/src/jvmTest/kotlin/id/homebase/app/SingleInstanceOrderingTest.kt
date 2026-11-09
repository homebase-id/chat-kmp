package id.homebase.app

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SingleInstanceOrderingTest {
    @Test
    fun `a second launch exits before touching app data or the database`() {
        val home = Files.createTempDirectory("single-instance-home").toFile()
        val tmp = Files.createTempDirectory("single-instance-tmp").toFile()
        val savedTmp = System.getProperty("java.io.tmpdir")
        System.setProperty("java.io.tmpdir", tmp.absolutePath)
        try {
            val restores = AtomicInteger()
            assertTrue(claimSingleInstance { restores.incrementAndGet() })

            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            val process = ProcessBuilder(
                java,
                "-Djava.awt.headless=true",
                "-Duser.home=${home.absolutePath}",
                "-Djava.io.tmpdir=${tmp.absolutePath}",
                "-cp", System.getProperty("java.class.path"),
                "id.homebase.app.MainKt",
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()

            val exited = process.waitFor(60, TimeUnit.SECONDS)
            if (!exited) process.destroyForcibly()
            assertTrue(exited, "second launch did not exit")

            assertEquals(emptyList(), home.walkTopDown().filter { it != home }.toList(), "second launch wrote under user.home")
            val deadline = System.currentTimeMillis() + 5_000
            while (restores.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(restores.get() > 0, "first instance was not asked to restore its window")
        } finally {
            System.setProperty("java.io.tmpdir", savedTmp)
            home.deleteRecursively()
            tmp.deleteRecursively()
        }
    }
}
