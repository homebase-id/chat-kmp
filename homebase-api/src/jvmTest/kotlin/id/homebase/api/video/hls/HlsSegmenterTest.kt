package id.homebase.api.video.hls

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.Blake3Hasher
import id.homebase.api.crypto.plaintextContentHash
import id.homebase.api.file.OkioFileOperationsProvider
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import org.junit.Test

/**
 * The fixtures are 14 s of 320x180 H.264 at 30 fps (420 frames, a keyframe every 2 s) and 48 kHz
 * AAC (658 packets, the first priming): `moov_first`, the same with its moov after the media, and one rotated 90°.
 */
class HlsSegmenterTest {
    private val cache = Files.createTempDirectory("hls").toFile()
    private val fileOps = OkioFileOperationsProvider(FileSystem.SYSTEM, cache.absolutePath)
    private val keyHeader = KeyHeader(Random.nextBytes(16), SecureByteArray(Random.nextBytes(16)))

    private fun fixture(name: String): String {
        val file = File(cache, name)
        javaClass.getResourceAsStream("/hls/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file.absolutePath
    }

    private class Range(val seconds: Double, val length: Int, val offset: Int)

    private fun ranges(playlist: String): List<Range> {
        val lines = playlist.lines()
        return lines.indices.filter { lines[it].startsWith("#EXTINF:") }.map { i ->
            val (length, offset) = lines[i + 1].removePrefix("#EXT-X-BYTERANGE:").split("@").map(String::toInt)
            Range(lines[i].removePrefix("#EXTINF:").trimEnd(',').toDouble(), length, offset)
        }
    }

    private suspend fun segmentAndCheck(name: String) {
        val segmented = assertNotNull(HlsSegmenter.segmentAndEncrypt(fileOps, fixture(name), keyHeader))
        val playlist = File(segmented.playlistPath).readText()
        val stored = File(segmented.segmentsPath).readBytes()

        val iv = keyHeader.iv.toHexString()
        assertTrue(playlist.startsWith("#EXTM3U\n#EXT-X-VERSION:4\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n"), playlist)
        assertTrue("#EXT-X-KEY:METHOD=AES-128,URI=\"enc.key\",IV=0x$iv\n" in playlist, playlist)
        assertTrue(playlist.trimEnd().endsWith("#EXT-X-ENDLIST"), playlist)

        // Cut at the first keyframe 6 s or more into each segment; the ranges tile the file.
        val ranges = ranges(playlist)
        assertEquals(listOf(6.0, 6.0, 2.0), ranges.map { it.seconds }.map { (it * 1000).toInt() / 1000.0 }, playlist)
        assertEquals(stored.size, ranges.sumOf { it.length })
        ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.offset + a.length, b.offset) }

        // Each range decrypts on its own under the one IV, as players read it.
        val plain = ranges.map { AesCbc.decrypt(stored.copyOfRange(it.offset, it.offset + it.length), keyHeader.aesKey, keyHeader.iv) }
        val ts = plain.reduce(ByteArray::plus)
        val packets = TsPackets(ts)
        assertEquals(420, packets.pesStarts(0x100), "video frames")
        assertEquals(658, packets.pesStarts(0x101), "audio frames, as ffmpeg's own TS carries them")
        assertEquals(3, packets.pesStarts(0x1000), "a PMT at each segment's start")
        assertTrue(packets.continuityHolds(), "continuity counters")

        val hash = assertNotNull(segmented.hash)
        assertEquals("blake3", hash.algorithm)
        assertContentEquals(blake3(stored), hash.storedHash!!.bytes)
        assertContentEquals(blake3(ts), hash.plaintextContentHash(keyHeader))

        playsInFfmpeg(File(segmented.playlistPath).parentFile, ts)
    }

    @Test
    fun aVideoIsCutIntoEncryptedSegmentsOfAboutSixSecondsWithItsPayloadHash() = runTest { segmentAndCheck("moov_first.mp4") }

    @Test
    fun withoutAKeyTheSegmentsAndPlaylistAreInTheClearWithABlake3HashOfTheFile() = runTest {
        val segmented = assertNotNull(HlsSegmenter.segmentAndEncrypt(fileOps, fixture("moov_first.mp4"), null))
        val playlist = File(segmented.playlistPath).readText()
        val stored = File(segmented.segmentsPath).readBytes()

        assertTrue("EXT-X-KEY" !in playlist, playlist)
        assertEquals(0x47, stored[0].toInt())
        val hash = assertNotNull(segmented.hash)
        assertEquals("blake3", hash.algorithm)
        assertContentEquals(blake3(stored), hash.storedHash?.bytes)
        assertContentEquals(blake3(stored), hash.contentHash?.bytes)
        assertContentEquals(blake3(stored), hash.plaintextContentHash(null))
    }

    @Test
    fun aMoovAfterTheMediaIsReadWithoutAPassOverIt() = runTest { segmentAndCheck("moov_last.mp4") }

    @Test
    fun aRotatedVideoIsLeftToFfmpeg() = runTest {
        assertNull(HlsSegmenter.segmentAndEncrypt(fileOps, fixture("rotated.mp4"), keyHeader))
    }

    @Test
    fun anythingButAnMp4IsLeftToFfmpeg() = runTest {
        val notMp4 = File(cache, "not.mp4").apply { writeBytes(Random.nextBytes(4096)) }
        assertNull(HlsSegmenter.segmentAndEncrypt(fileOps, notMp4.absolutePath, keyHeader))
    }

    @Test
    fun theSegmentsAndDecodedFramesMatchFfmpegsOwnHls() = runTest {
        val ffmpeg = tool("ffmpeg") ?: return@runTest
        for (name in listOf("moov_first.mp4", "moov_last.mp4")) {
            val input = fixture(name)
            val ours = assertNotNull(HlsSegmenter.segmentAndEncrypt(fileOps, input, keyHeader))
            val oursDir = File(ours.playlistPath).parentFile
            File(oursDir, "enc.key").writeBytes(keyHeader.aesKey.unsafeBytes)

            // ffmpeg's own HLS, cut with the arguments the Android ffmpeg path uses.
            val theirsDir = Files.createTempDirectory(cache.toPath(), "ffmpeg").toFile()
            File(theirsDir, "enc.key").writeBytes(keyHeader.aesKey.unsafeBytes)
            val keyInfo = File(theirsDir, "keyinfo.txt").apply { writeText("enc.key\n${File(theirsDir, "enc.key").absolutePath}\n${keyHeader.iv.toHexString()}\n") }
            run(ffmpeg, "-v", "error", "-y", "-i", input, "-codec:v", "copy", "-codec:a", "copy", "-hls_time", "6", "-hls_list_size", "0",
                "-hls_flags", "single_file", "-hls_key_info_file", keyInfo.absolutePath, "-f", "hls",
                "-hls_segment_filename", File(theirsDir, "index.ts").absolutePath, File(theirsDir, "index.m3u8").absolutePath)

            val oursRanges = ranges(File(ours.playlistPath).readText())
            val theirsRanges = ranges(File(theirsDir, "index.m3u8").readText())
            assertEquals(theirsRanges.map { (it.seconds * 1000).toInt() }, oursRanges.map { (it.seconds * 1000).toInt() }, "$name: the same cuts")
            for (stream in listOf("v:0", "a:0")) {
                assertEquals(framemd5(ffmpeg, theirsDir, stream), framemd5(ffmpeg, oursDir, stream), "$name: the same decoded $stream")
            }
        }
    }

    private fun tool(name: String): String? = name.takeIf { runCatching { ProcessBuilder(it, "-version").start().waitFor() == 0 }.getOrDefault(false) }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), out)
        return out
    }

    // Each decoded frame's checksum, timestamps dropped (ffmpeg's TS starts its clock 1.4 s in, as ours does).
    private fun framemd5(ffmpeg: String, dir: File, stream: String): List<String> =
        run(ffmpeg, "-v", "error", "-allowed_extensions", "ALL", "-i", File(dir, "index.m3u8").absolutePath, "-map", "0:$stream", "-f", "framemd5", "-")
            .lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split(",").map(String::trim).let { f -> "${f[4]} ${f[5]}" } }

    private fun blake3(bytes: ByteArray) = Blake3Hasher().apply { update(bytes) }.digest()

    /** With an ffmpeg on the machine, it plays the encrypted playlist as a player would and counts the frames. */
    private fun playsInFfmpeg(dir: File, ts: ByteArray) {
        val ffmpeg = listOf("ffmpeg", "ffprobe").takeIf { tools -> tools.all { runCatching { ProcessBuilder(it, "-version").start().waitFor() == 0 }.getOrDefault(false) } } ?: return
        File(dir, "enc.key").writeBytes(keyHeader.aesKey.unsafeBytes)
        val play = ProcessBuilder(ffmpeg[0], "-v", "error", "-allowed_extensions", "ALL", "-i", File(dir, "index.m3u8").absolutePath, "-f", "null", "-")
            .redirectErrorStream(true).start()
        val errors = play.inputStream.bufferedReader().readText()
        assertEquals(0, play.waitFor(), errors)
        assertEquals("", errors.trim(), "ffmpeg decode errors")
        val plain = File(dir, "plain.ts").apply { writeBytes(ts) }
        val probe = ProcessBuilder(ffmpeg[1], "-v", "error", "-count_packets", "-show_entries", "stream=codec_name,nb_read_packets:format=duration", "-of", "compact", plain.absolutePath)
            .redirectErrorStream(true).start()
        val out = probe.inputStream.bufferedReader().readText()
        probe.waitFor()
        assertTrue("codec_name=h264|nb_read_packets=420" in out, out)
        assertTrue("codec_name=aac|nb_read_packets=658" in out, out)
        val duration = Regex("duration=([0-9.]+)").find(out)!!.groupValues[1].toDouble()
        assertTrue(abs(duration - 14.0) < 0.2, out)
    }

    private class TsPackets(private val ts: ByteArray) {
        init {
            require(ts.size % 188 == 0) { "not whole packets: ${ts.size}" }
            for (at in ts.indices step 188) require(ts[at] == 0x47.toByte()) { "no sync byte at $at" }
        }

        private fun pid(at: Int) = ((ts[at + 1].toInt() and 0x1F) shl 8) or (ts[at + 2].toInt() and 0xFF)

        fun pesStarts(pid: Int) = (ts.indices step 188).count { pid(it) == pid && ts[it + 1].toInt() and 0x40 != 0 }

        fun continuityHolds(): Boolean {
            val last = HashMap<Int, Int>()
            for (at in ts.indices step 188) {
                val pid = pid(at)
                val counter = ts[at + 3].toInt() and 0xF
                val previous = last[pid]
                if (previous != null && counter != (previous + 1) and 0xF) return false
                last[pid] = counter
            }
            return true
        }
    }
}
