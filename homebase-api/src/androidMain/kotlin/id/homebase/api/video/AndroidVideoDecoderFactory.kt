package id.homebase.api.video

// MediaCodec primary, MediaMetadataRetriever fallback — same composition shape as iOS / Web.
// Tier-runner handles: poster falls through on null/throw; strip emits only the indices the
// primary couldn't cover.
internal actual fun platformVideoDecoder(): VideoDecoder =
    TieredVideoDecoder(
        primary = MediaCodecVideoDecoder(),
        fallback = MmrVideoDecoder(),
    )

// No ffmpeg on Android: native MediaCodec → MediaMetadataRetriever is forgiving enough for the codec mix we ship for.
internal actual fun platformFfmpegDecoder(): VideoDecoder? = null
