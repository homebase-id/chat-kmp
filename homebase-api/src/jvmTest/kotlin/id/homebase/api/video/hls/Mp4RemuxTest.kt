package id.homebase.api.video.hls

import id.homebase.api.TestTempDirs
import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.api.video.mp4Boxes
import id.homebase.api.video.readMoov
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import org.junit.Test

/** A video sent as it is: the source's own frames and audio in a new MP4 that carries nothing else. */
class Mp4RemuxTest {
    private val cache = TestTempDirs.dir("mp4remux").toFile()
    private val fileOps = OkioFileOperationsProvider(FileSystem.SYSTEM, cache.absolutePath)

    @Test
    fun aVideoWithItsIndexFirstComesOutFrameForFrameWithoutItsSei() = runTest { check("moov_first.mp4") }

    @Test
    fun aVideoWithItsIndexLastToo() = runTest { check("moov_last.mp4") }

    @Test
    fun anSeiUnitIsDroppedAndEveryOtherUnitKept() {
        val sei = byteArrayOf(0, 0, 0, 3, 0x06, 0x05, 0x01)
        val idr = byteArrayOf(0, 0, 0, 2, 0x65, 0x11)
        assertContentEquals(idr, Mp4Remux.withoutSei(sei + idr))
        assertContentEquals(idr, Mp4Remux.withoutSei(idr), "nothing to drop")
    }

    private suspend fun check(name: String) {
        val source = hlsFixture(cache, name)
        assertTrue(source.readBytes().decodeToString().contains("x264 - core"), "the fixture carries x264's SEI")
        val movie = assertNotNull(Mp4Movie.parse(assertNotNull(readMoov(fileOps, source.absolutePath))))
        val out = File(cache, "$name.out.mp4")
        Mp4Remux.remux(fileOps, source.absolutePath, movie, out.absolutePath)

        val written = out.readBytes()
        assertEquals(listOf("ftyp", "mdat", "moov"), mp4Boxes(written, 0, written.size).map { it.type })
        val moov = mp4Boxes(written, 0, written.size).single { it.type == "moov" }
        assertEquals(listOf("mvhd", "trak", "trak"), mp4Boxes(written, moov.start, moov.end).map { it.type }, "the video and audio tracks, no metadata")
        assertFalse(written.decodeToString().contains("x264 - core"), "no SEI")

        val ours = assertNotNull(Mp4Movie.parse(assertNotNull(readMoov(fileOps, out.absolutePath))))
        val original = source.readBytes()
        for (video in listOf(true, false)) {
            val a = movie.samples.filter { (it.track is Mp4Track.Avc) == video }
            val b = ours.samples.filter { (it.track is Mp4Track.Avc) == video }
            assertEquals(a.size, b.size)
            assertEquals(a.map { it.isKey }, b.map { it.isKey })
            assertEquals(a.map { it.pts90k - a.first().pts90k }, b.map { it.pts90k - b.first().pts90k }, "timing")
            a.zip(b).forEach { (x, y) ->
                val sourceBytes = original.copyOfRange(x.offset.toInt(), x.offset.toInt() + x.size)
                assertContentEquals(if (video) Mp4Remux.withoutSei(sourceBytes) else sourceBytes, written.copyOfRange(y.offset.toInt(), y.offset.toInt() + y.size))
            }
        }
        decodesInFfmpeg(source, out)
    }
}
