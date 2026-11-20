package id.homebase.api.video.hls

import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.api.video.mp4Boxes
import id.homebase.api.video.readMoov
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import org.junit.Test

/** A video sent as it is: the source's own samples in a new MP4 that carries nothing else. */
class Mp4RemuxTest {
    private val cache = Files.createTempDirectory("mp4remux").toFile()
    private val fileOps = OkioFileOperationsProvider(FileSystem.SYSTEM, cache.absolutePath)

    @Test
    fun aVideoWithItsIndexFirstComesOutSampleForSample() = runTest { check("moov_first.mp4") }

    @Test
    fun aVideoWithItsIndexLastToo() = runTest { check("moov_last.mp4") }

    private suspend fun check(name: String) {
        val source = File(cache, name).apply { Mp4RemuxTest::class.java.getResourceAsStream("/hls/$name")!!.use { i -> outputStream().use { i.copyTo(it) } } }
        val movie = assertNotNull(Mp4Movie.parse(assertNotNull(readMoov(fileOps, source.absolutePath))))
        val out = File(cache, "$name.out.mp4")
        Mp4Remux.remux(fileOps, source.absolutePath, movie, out.absolutePath)

        assertSameMedia(fileOps, source, out)
        val written = out.readBytes()
        val moov = mp4Boxes(written, 0, written.size).single { it.type == "moov" }
        val children = mp4Boxes(written, moov.start, moov.end)
        assertEquals(listOf("mvhd", "trak", "trak"), children.map { it.type }, "the video and audio tracks, no metadata")
    }
}
