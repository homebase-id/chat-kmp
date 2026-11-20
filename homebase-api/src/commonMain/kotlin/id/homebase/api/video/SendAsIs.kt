package id.homebase.api.video

import id.homebase.api.video.hls.Mp4Movie
import id.homebase.api.video.hls.Mp4Track
import id.homebase.api.video.hls.readSps

/** The probe's half of [meetsWithoutTranscode]: not HDR, and no larger than the setting. A probe that can't tell counts as HDR. */
internal fun mightMeetWithoutTranscode(info: VideoTrackInfo, quality: VideoQuality): Boolean {
    if (info.isHdr != false) return false
    val shortEdge = minOf(info.widthPx, info.heightPx)
    return shortEdge == 0 || shortEdge <= quality.targets().shortEdgePx
}

/** Whether [movie] already is what [quality]'s transcode would make, so it can be sent as it is (THUMBNAILS.md, "Video"). */
internal fun meetsWithoutTranscode(movie: Mp4Movie, quality: VideoQuality): Boolean {
    if (movie.trimmedByEdits || movie.video.nalLengthSize != 4) return false
    val sps = movie.video.sps.firstOrNull()?.let(::readSps) ?: return false
    if (sps.bitDepthLuma != 8 || sps.bitDepthChroma != 8 || sps.chromaFormat != 1) return false
    val audio = movie.audio
    if (audio != null && (audio.profile != AAC_LC_PROFILE || audio.channels !in 1..2)) return false
    val targets = quality.targets()
    if (minOf(sps.width, sps.height) > targets.shortEdgePx) return false
    val seconds = (movie.end90k - movie.start90k) / 90_000.0
    if (seconds <= 0) return false
    val videoBits = movie.samples.filter { it.track is Mp4Track.Avc }.sumOf { it.size.toLong() } * 8
    return videoBits / seconds <= targets.hardwareVideoBitrate(sps.width, sps.height, null)
}

// ADTS's profile field: the audio object type less one.
private const val AAC_LC_PROFILE = 1
