package id.homebase.api.video

import id.homebase.api.video.hls.Mp4Movie
import id.homebase.api.video.hls.Mp4Sample
import id.homebase.api.video.hls.Mp4Track
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * When a video goes out without a transcode (THUMBNAILS.md, "Video"), on every platform. The fixture is 6 s of 320x180
 * 8-bit H.264 with stereo AAC-LC at a low bitrate, so it meets both settings; each case changes one thing that must
 * force a transcode.
 */
class SendAsIsRuleTest {
    private fun moov(bytes: ByteArray = SampleVideoFixture.bytes): ByteArray {
        val box = mp4Boxes(bytes, 0, bytes.size).single { it.type == "moov" }
        return bytes.copyOfRange(box.start, box.end)
    }

    private fun movie(bytes: ByteArray = SampleVideoFixture.bytes) = assertNotNull(Mp4Movie.parse(moov(bytes)))

    private fun Mp4Movie.with(video: Mp4Track.Avc = this.video, audio: Mp4Track.Aac? = this.audio, samples: List<Mp4Sample> = this.samples) =
        Mp4Movie(video, audio, samples, start90k, end90k, trimmedByEdits)

    @Test
    fun aSmallSdrH264WithAacLcMeetsBothSettings() {
        for (quality in listOf(VideoQuality.STANDARD, VideoQuality.HIGH)) assertTrue(meetsWithoutTranscode(movie(), quality), "$quality")
    }

    @Test
    fun hdrAProbeThatCantTellOrALargerFrameIsTranscoded() {
        assertTrue(mightMeetWithoutTranscode(VideoTrackInfo("h264", 1280, 720, 8, isHdr = false), VideoQuality.STANDARD))
        assertTrue(mightMeetWithoutTranscode(VideoTrackInfo("h264", 0, 0, 8, isHdr = false), VideoQuality.STANDARD), "size unknown: the moov decides")
        assertFalse(mightMeetWithoutTranscode(VideoTrackInfo("h264", 1280, 720, 8, isHdr = true), VideoQuality.STANDARD))
        assertFalse(mightMeetWithoutTranscode(VideoTrackInfo("h264", 1280, 720, 8, isHdr = null), VideoQuality.STANDARD))
        assertFalse(mightMeetWithoutTranscode(VideoTrackInfo("h264", 1920, 1080, 8, isHdr = false), VideoQuality.STANDARD))
    }

    @Test
    fun aBitrateOverWhatTheEncoderWouldAskForIsTranscoded() {
        // A 320x180 transcode asks for 156 kbps at Standard and 139 kbps at HD.
        val movie = movie()
        val bitrate = movie.samples.filter { it.track is Mp4Track.Avc }.sumOf { it.size.toLong() } * 8 / ((movie.end90k - movie.start90k) / 90_000.0)
        val over = (200_000 / bitrate).toInt() + 1
        val heavy = movie.with(samples = movie.samples.map {
            if (it.track is Mp4Track.Avc) Mp4Sample(it.track, it.offset, it.size * over, it.dts90k, it.pts90k, it.isKey) else it
        })
        for (quality in listOf(VideoQuality.STANDARD, VideoQuality.HIGH)) assertFalse(meetsWithoutTranscode(heavy, quality), "$quality")
    }

    @Test
    fun audioOtherThanStereoAacLcIsTranscoded() {
        val movie = movie()
        val aac = assertNotNull(movie.audio)
        assertFalse(meetsWithoutTranscode(movie.with(audio = Mp4Track.Aac(0, aac.sampleRateIndex, 2)), VideoQuality.STANDARD), "AAC Main")
        assertFalse(meetsWithoutTranscode(movie.with(audio = Mp4Track.Aac(1, aac.sampleRateIndex, 6)), VideoQuality.STANDARD), "5.1")
        assertTrue(meetsWithoutTranscode(movie.with(audio = null), VideoQuality.STANDARD), "no audio")
    }

    @Test
    fun nalLengthsOtherThanFourBytesAreTranscoded() {
        val movie = movie()
        assertFalse(meetsWithoutTranscode(movie.with(video = Mp4Track.Avc(2, movie.video.sps, movie.video.pps)), VideoQuality.STANDARD))
    }

    @Test
    fun aFileTrimmedByItsEditListIsTranscoded() {
        // The fixture's edits show all 6 s; halve the first one's duration, as an editor's trim does.
        val bytes = SampleVideoFixture.bytes.copyOf()
        val elst = bytes.indexOfSequence("elst".encodeToByteArray())
        val durationAt = elst + 12
        val duration = ((bytes[durationAt].toInt() and 0xFF) shl 24) or ((bytes[durationAt + 1].toInt() and 0xFF) shl 16) or
            ((bytes[durationAt + 2].toInt() and 0xFF) shl 8) or (bytes[durationAt + 3].toInt() and 0xFF)
        val half = duration / 2
        for (i in 0..3) bytes[durationAt + i] = (half ushr (24 - 8 * i)).toByte()
        val trimmed = movie(bytes)
        assertTrue(trimmed.trimmedByEdits)
        assertFalse(meetsWithoutTranscode(trimmed, VideoQuality.STANDARD))
        assertFalse(movie().trimmedByEdits, "an edit that only skips the encoder's start delay isn't a trim")
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int =
        (0..size - needle.size).first { i -> needle.indices.all { this[i + it] == needle[it] } }
}
