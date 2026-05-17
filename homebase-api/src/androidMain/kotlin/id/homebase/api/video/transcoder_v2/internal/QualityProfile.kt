package id.homebase.api.video.transcoder_v2.internal

import android.media.MediaFormat
import id.homebase.api.video.VideoQuality

/**
 * Encoder configuration derived from the public [VideoQuality] enum. See
 * SPEC §6. Bitrate-mode is VBR (`MediaFormat`'s implicit default).
 */
internal data class QualityProfile(
    val videoCodec: String,        // MIME, e.g. "video/avc"
    val shortEdgePx: Int,
    val videoBitrateBps: Int,
    val audioCodec: String,        // "audio/mp4a-latm"
    val audioBitrateBps: Int,
)

internal fun VideoQuality.profile(): QualityProfile = when (this) {
    VideoQuality.LOW -> QualityProfile(
        videoCodec = MediaFormat.MIMETYPE_VIDEO_AVC,
        shortEdgePx = 480,
        videoBitrateBps = 1_250_000,
        audioCodec = MediaFormat.MIMETYPE_AUDIO_AAC,
        audioBitrateBps = 128_000,
    )
    VideoQuality.STANDARD -> QualityProfile(
        videoCodec = MediaFormat.MIMETYPE_VIDEO_AVC,
        shortEdgePx = 720,
        videoBitrateBps = 2_500_000,
        audioCodec = MediaFormat.MIMETYPE_AUDIO_AAC,
        audioBitrateBps = 128_000,
    )
    VideoQuality.HIGH -> QualityProfile(
        videoCodec = MediaFormat.MIMETYPE_VIDEO_AVC,
        shortEdgePx = 1080,
        videoBitrateBps = 5_000_000,
        audioCodec = MediaFormat.MIMETYPE_AUDIO_AAC,
        audioBitrateBps = 192_000,
    )
}
