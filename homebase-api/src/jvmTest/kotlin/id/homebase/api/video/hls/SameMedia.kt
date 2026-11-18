package id.homebase.api.video.hls

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.video.mp4Boxes
import id.homebase.api.video.readMoov
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [out] holds [source]'s own media: the index after the samples, every sample byte for byte with its timing and key flag,
 * and, with an ffmpeg on the machine, the same decoded frames, audio packets and A/V offset.
 */
internal suspend fun assertSameMedia(fileOps: FileOperationsProvider, source: File, out: File) {
    val written = out.readBytes()
    val boxes = mp4Boxes(written, 0, written.size)
    // The mdat's size is written back last; only the right one lets the walk reach the moov and the file's end.
    assertEquals(listOf("ftyp", "mdat", "moov"), boxes.map { it.type })
    assertEquals(written.size, boxes.last().end)
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
    // In sync: audio starts as far from the video as in the source, within the AAC priming frame (21 ms) the source's
    // edit list hides.
    fun start(file: File, stream: String) = ProcessBuilder("ffprobe", "-v", "error", "-select_streams", stream, "-show_entries", "stream=start_time", "-of", "csv=p=0", file.absolutePath)
        .start().inputStream.bufferedReader().readText().trim().toDouble()
    val sourceGap = start(source, "a") - start(source, "v")
    val ourGap = start(out, "a") - start(out, "v")
    assertTrue(kotlin.math.abs(sourceGap - ourGap) < 0.0215, "audio against video: source $sourceGap s, ours $ourGap s")
}
