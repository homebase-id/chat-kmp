package id.homebase.api.video

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test

class TmpRemuxCrashBisectJvmTest {

    private fun run(label: String, args: List<String>, tailLines: Int = 4) {
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readLines()
        p.waitFor(60, TimeUnit.SECONDS)
        println("BISECT $label exit=${p.exitValue()}")
        if (p.exitValue() != 0) out.takeLast(tailLines).forEach { println("BISECT   | $it") }
    }

    @Test
    fun bisect() {
        if (!FFmpegBinaryManager.isAvailable()) return
        val ff = FFmpegBinaryManager.ffmpegPath()
        val fp = FFmpegBinaryManager.ffprobePath()
        val dir = Files.createTempDirectory("bisect").toFile()
        try {
            val ts = File(dir, "in.ts").absolutePath
            val mp4 = File(dir, "in.mp4").absolutePath
            val src = listOf(
                "-f", "lavfi", "-i", "testsrc=size=160x90:rate=10:duration=2",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-c:v", "libx264", "-g", "10", "-pix_fmt", "yuv420p", "-c:a", "aac",
            )
            run("gen-ts", listOf(ff, "-y") + src + listOf("-f", "mpegts", ts))
            run("gen-mp4", listOf(ff, "-y") + src + listOf(mp4))
            fun o(n: String) = File(dir, n).absolutePath
            run("ffprobe-ts", listOf(fp, ts))
            run("ffprobe-mp4", listOf(fp, mp4))
            run("ts-null", listOf(ff, "-y", "-i", ts, "-f", "null", "-"))
            run("ts-copy-mkv", listOf(ff, "-y", "-i", ts, "-c", "copy", o("a.mkv")))
            run("ts-copy-ts", listOf(ff, "-y", "-i", ts, "-c", "copy", o("a.ts")))
            run("ts-copy-mp4", listOf(ff, "-y", "-i", ts, "-c", "copy", o("b.mp4")))
            run("ts-copy-mp4-bsf", listOf(ff, "-y", "-i", ts, "-c", "copy", "-bsf:a", "aac_adtstoasc", o("c.mp4")))
            run("ts-copy-mp4-faststart", listOf(ff, "-y", "-i", ts, "-c", "copy", "-movflags", "+faststart", o("d.mp4")))
            run("ts-forced-mpegts-full", listOf(ff, "-y", "-f", "mpegts", "-i", ts, "-c", "copy", "-bsf:a", "aac_adtstoasc", "-movflags", "+faststart", o("e.mp4")))
            run("ts-vonly-copy-mp4", listOf(ff, "-y", "-i", ts, "-an", "-c", "copy", o("f.mp4")))
            run("ts-aonly-copy-mp4", listOf(ff, "-y", "-i", ts, "-vn", "-c", "copy", o("g.m4a")))
            run("ts-nostdin-full", listOf(ff, "-nostdin", "-y", "-i", ts, "-c", "copy", "-bsf:a", "aac_adtstoasc", "-movflags", "+faststart", o("h.mp4")))
            run("mp4-copy-mp4", listOf(ff, "-y", "-i", mp4, "-c", "copy", o("i.mp4")))
            run("mp4-to-ts-copy", listOf(ff, "-y", "-i", mp4, "-c", "copy", o("j.ts")))
            run("ts-full-debug", listOf(ff, "-v", "debug", "-y", "-i", ts, "-c", "copy", "-bsf:a", "aac_adtstoasc", "-movflags", "+faststart", o("k.mp4")), tailLines = 40)
        } finally {
            dir.deleteRecursively()
        }
    }
}
