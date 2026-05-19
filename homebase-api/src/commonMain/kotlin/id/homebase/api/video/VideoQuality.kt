package id.homebase.api.video

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
}
