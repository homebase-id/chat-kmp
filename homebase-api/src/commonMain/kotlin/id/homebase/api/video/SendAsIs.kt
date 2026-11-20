package id.homebase.api.video

import id.homebase.api.video.hls.Mp4Movie
import id.homebase.api.video.hls.Mp4Track
import id.homebase.api.video.hls.readSps

/**
 * Whether [movie] already is what [quality]'s transcode would make (THUMBNAILS.md, "Video"), so it can be sent as it
 * is: 8-bit 4:2:0 SDR H.264 with 4-byte NAL lengths, AAC-LC in at most two channels or no audio, and within the
 * setting's short edge and video bitrate. [Mp4Movie] itself only holds an upright H.264 and AAC file. [isHdr] is the
 * probe's answer; one that can't tell counts as HDR.
 */
internal fun meetsWithoutTranscode(movie: Mp4Movie, isHdr: Boolean?, quality: VideoQuality): Boolean {
    if (isHdr != false || movie.video.nalLengthSize != 4) return false
    val sps = movie.video.sps.firstOrNull()?.let(::readSps) ?: return false
    if (sps.bitDepthLuma != 8 || sps.bitDepthChroma != 8 || sps.chromaFormat != 1) return false
    val audio = movie.audio
    if (audio != null && (audio.profile != AAC_LC_PROFILE || audio.channels !in 1..2)) return false
    val targets = quality.targets()
    if (minOf(sps.width, sps.height) > targets.shortEdgePx) return false
    val seconds = (movie.end90k - movie.start90k) / 90_000.0
    if (seconds <= 0) return false
    val videoBits = movie.samples.filter { it.track is Mp4Track.Avc }.sumOf { it.size.toLong() } * 8
    return videoBits / seconds <= targets.videoBitrateBps
}

// ADTS's profile field: the audio object type less one.
private const val AAC_LC_PROFILE = 1
