package id.homebase.api.video.hls

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesCbc
import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.api.video.VideoCompressionService
import id.homebase.api.video.mp4Boxes
import id.homebase.api.video.readMoov
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
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
    fun anythingButATsFailsAndLeavesNoOutput() = runTest {
        val notTs = File(cache, "not.ts").apply { writeBytes(Random.nextBytes(100_000)) }
        val out = File(cache, "not.mp4")
        assertFalse(VideoCompressionService.remuxTsToMp4(fileOps, notTs.absolutePath, out.absolutePath))
        assertFalse(out.exists())
    }

    @Test
    fun theFrameSizeComesFromTheSps() {
        // 1280x720 High profile, cropped from 1280x736 macroblocks.
        val sps = "6764001facd9405005bb011000000300100000030300f1831960".hexToByteArray()
        val read = assertNotNull(readSps(sps))
        assertEquals(1280 to 720, read.width to read.height)
        assertEquals(Triple(1, 8, 8), Triple(read.chromaFormat, read.bitDepthLuma, read.bitDepthChroma))
    }

    private suspend fun check(name: String) {
        val source = File(cache, name).apply { TsToMp4Test::class.java.getResourceAsStream("/hls/$name")!!.use { i -> outputStream().use { i.copyTo(it) } } }
        val segmented = assertNotNull(HlsSegmenter.segmentAndEncrypt(fileOps, source.absolutePath, keyHeader))
        // As a download writes it: the whole payload decrypted as one CBC stream, though each segment was encrypted on
        // its own, so every boundary leaves pad bytes and a garbled block for the demuxer to skip.
        val ts = File(cache, "$name.ts").apply {
            writeBytes(AesCbc.streamDecryptWithCbc(flowOf(File(segmented.segmentsPath).readBytes()), keyHeader.aesKey, keyHeader.iv).toList().reduce(ByteArray::plus))
        }
        assertTrue(segmented.playlistPath.let { File(it).readText().split("#EXTINF").size } > 2, "several segments, so boundaries are crossed")
        val out = File(cache, "$name.out.mp4")
        TsToMp4.remux(fileOps, ts.absolutePath, out.absolutePath)

        assertSameMedia(fileOps, source, out)
    }
}
