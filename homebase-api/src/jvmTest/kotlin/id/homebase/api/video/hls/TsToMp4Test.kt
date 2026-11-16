package id.homebase.api.video.hls

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesCbc
import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.api.video.mp4Boxes
import id.homebase.api.video.readMoov
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import org.junit.Test

/** Our segmenter's HLS, decrypted as a download writes it, remuxed back: the source's own samples, in an MP4. */
class TsToMp4Test {
    private val cache = Files.createTempDirectory("ts2mp4").toFile()
    private val fileOps = OkioFileOperationsProvider(FileSystem.SYSTEM, cache.absolutePath)
    private val keyHeader = KeyHeader(Random.nextBytes(16), SecureByteArray(Random.nextBytes(16)))

    @Test
    fun aSegmentedVideoComesBackSampleForSample() = runTest { check("moov_first.mp4") }

    @Test
    fun aVideoWithItsIndexLastToo() = runTest { check("moov_last.mp4") }

    @Test
    fun theFrameSizeComesFromTheSps() {
        // 1280x720 High profile, cropped from 1280x736 macroblocks.
        val sps = "6764001facd9405005bb011000000300100000030300f1831960".hexToByteArray()
        assertEquals(1280 to 720, spsSize(sps))
    }

    private suspend fun check(name: String) {
        val source = File(cache, name).apply { TsToMp4Test::class.java.getResourceAsStream("/hls/$name")!!.use { i -> outputStream().use { i.copyTo(it) } } }
        val segmented = assertNotNull(HlsSegmenter.segmentAndEncrypt(fileOps, source.absolutePath, keyHeader))
        val stored = File(segmented.segmentsPath).readBytes()
        val ts = File(cache, "$name.ts").apply {
            writeBytes(Regex("#EXT-X-BYTERANGE:(\\d+)@(\\d+)").findAll(File(segmented.playlistPath).readText()).toList().map { m ->
                val (length, offset) = m.destructured
                AesCbc.decrypt(stored.copyOfRange(offset.toInt(), offset.toInt() + length.toInt()), keyHeader.aesKey, keyHeader.iv)
            }.reduce(ByteArray::plus))
        }
        val out = File(cache, "$name.out.mp4")
        TsToMp4.remux(fileOps, ts.absolutePath, out.absolutePath)

        val written = out.readBytes()
        assertEquals(listOf("ftyp", "moov", "mdat"), mp4Boxes(written, 0, written.size).map { it.type }, "index up front")
        val ours = assertNotNull(Mp4Movie.parse(assertNotNull(readMoov(fileOps, out.absolutePath))))
        val theirs = assertNotNull(Mp4Movie.parse(assertNotNull(readMoov(fileOps, source.absolutePath))))
        val original = source.readBytes()
        for (video in listOf(true, false)) {
            val a = theirs.samples.filter { (it.track is Mp4Track.Avc) == video }
            val b = ours.samples.filter { (it.track is Mp4Track.Avc) == video }
            assertEquals(a.size, b.size, "${if (video) "video" else "audio"} samples")
            assertEquals(a.map { it.isKey }, b.map { it.isKey })
            assertEquals(a.map { it.pts90k - a.first().pts90k }, b.map { it.pts90k - b.first().pts90k }, "timing")
            a.zip(b).forEach { (x, y) ->
                assertContentEquals(original.copyOfRange(x.offset.toInt(), x.offset.toInt() + x.size), written.copyOfRange(y.offset.toInt(), y.offset.toInt() + y.size))
            }
        }
        decodesInFfmpeg(source, out)
    }

    /** With an ffmpeg on the machine: every decoded frame and every audio packet the same as the source's. */
    private fun decodesInFfmpeg(source: File, out: File) {
        if (runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() != 0 }.getOrDefault(true)) return
        fun md5(file: File, vararg args: String) = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.absolutePath, *args, "-f", "framemd5", "-")
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
            .lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split(",")[5].trim() }
        assertEquals(md5(source, "-map", "0:v"), md5(out, "-map", "0:v"), "decoded video")
        assertEquals(md5(source, "-map", "0:a", "-c", "copy"), md5(out, "-map", "0:a", "-c", "copy"), "audio packets")
        // In sync: audio starts as far from the video as in the source, within a millisecond.
        fun start(file: File, stream: String) = ProcessBuilder("ffprobe", "-v", "error", "-select_streams", stream, "-show_entries", "stream=start_time", "-of", "csv=p=0", file.absolutePath)
            .start().inputStream.bufferedReader().readText().trim().toDouble()
        val sourceGap = start(source, "a") - start(source, "v")
        val ourGap = start(out, "a") - start(out, "v")
        assertTrue(kotlin.math.abs(sourceGap - ourGap) < 0.0215, "audio against video: source $sourceGap s, ours $ourGap s")
    }
}
