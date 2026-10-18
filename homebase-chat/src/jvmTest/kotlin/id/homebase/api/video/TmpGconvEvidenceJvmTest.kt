package id.homebase.api.video

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test

class TmpGconvEvidenceJvmTest {

    private fun run(label: String, args: List<String>, env: Map<String, String> = emptyMap(), tail: Int = 6, grep: Regex? = null) {
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = try { pb.start() } catch (e: Exception) { println("EVID $label start-failed ${e.message}"); return }
        val out = p.inputStream.bufferedReader().readLines()
        p.waitFor(120, TimeUnit.SECONDS)
        println("EVID $label exit=${p.exitValue()}")
        val lines = if (grep != null) out.filter { grep.containsMatchIn(it) } else out
        lines.takeLast(tail).forEach { println("EVID   | $it") }
    }

    @Test
    fun evidence() {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        if (!FFmpegBinaryManager.isAvailable()) return
        val ff = FFmpegBinaryManager.ffmpegPath()
        val fp = FFmpegBinaryManager.ffprobePath()
        val dir = Files.createTempDirectory("evid").toFile()
        val emptyGconv = File(dir, "nogconv").apply { mkdirs() }.absolutePath
        val noGconv = mapOf("GCONV_PATH" to emptyGconv)
        try {
            val ts = File(dir, "in.ts").absolutePath
            fun o(n: String) = File(dir, n).absolutePath
            run("gen-ts", listOf(ff, "-y", "-f", "lavfi", "-i", "testsrc=size=160x90:rate=10:duration=2",
                "-c:v", "libx264", "-g", "10", "-pix_fmt", "yuv420p", "-f", "mpegts", ts))
            run("host-glibc", listOf("ldd", "--version"), tail = 1)
            run("host-gconv-dir", listOf("sh", "-c", "ls -d /usr/lib/*/gconv /usr/lib64/gconv 2>/dev/null; ls /usr/lib/x86_64-linux-gnu/gconv | grep -i 6937"))
            run("ffprobe-ts-default", listOf(fp, "-hide_banner", ts))
            run("ffprobe-ts-GCONV_PATH-empty", listOf(fp, "-hide_banner", ts), noGconv)
            run("remux-ts-default", listOf(ff, "-hide_banner", "-y", "-i", ts, "-c", "copy", o("a.mp4")))
            run("remux-ts-GCONV_PATH-empty", listOf(ff, "-hide_banner", "-y", "-i", ts, "-c", "copy", o("b.mp4")), noGconv)
            run("which-dbg", listOf("sh", "-c", "command -v gdb strace ltrace"))
            run("strace-gconv", listOf("strace", "-f", "-e", "trace=openat", fp, "-hide_banner", ts), tail = 20,
                grep = Regex("gconv|SIGSEGV|killed|6937"))
            run("gdb-bt", listOf("gdb", "-batch", "-ex", "run", "-ex", "bt 25", "--args", fp, "-hide_banner", ts), tail = 40)
        } finally {
            dir.deleteRecursively()
        }
    }
}
