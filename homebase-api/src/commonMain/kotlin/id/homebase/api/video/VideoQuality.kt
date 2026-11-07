package id.homebase.api.video

import id.homebase.api.image.MediaQuality

/**
 * Target quality for the compress step of the video send pipeline. Mapping
 * to a concrete encoder configuration is platform-specific (see each
 * [FFmpegUtils] actual).
 */
enum class VideoQuality {
    /** ~480p short edge, ~1.25 Mbps video + 128 kbps audio. */
    LOW,

    /** ~720p short edge, ~2.5 Mbps video + 128 kbps audio. Current default. */
    STANDARD,

    /** ~1080p short edge, ~5 Mbps video + 192 kbps audio. */
    HIGH,

    /** The file as it is, unless it's being trimmed: a trim has to re-encode, so that comes out as [HIGH]. */
    ORIGINAL,
}

// LOW is not reachable from the user-facing toggle; it stays for callers that ask for it directly.
fun MediaQuality.toVideoQuality(): VideoQuality = when (this) {
    MediaQuality.STANDARD -> VideoQuality.STANDARD
    MediaQuality.HD -> VideoQuality.HIGH
    MediaQuality.ORIGINAL -> VideoQuality.ORIGINAL
}
