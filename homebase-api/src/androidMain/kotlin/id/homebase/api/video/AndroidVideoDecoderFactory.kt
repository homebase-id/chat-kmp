package id.homebase.api.video

// MediaCodec primary, MediaMetadataRetriever fallback — same composition shape as iOS / Web.
// Tier-runner handles: poster falls through on null/throw; strip emits only the indices the
// primary couldn't cover.
internal actual fun platformVideoDecoder(): VideoDecoder =
    TieredVideoDecoder(
        primary = MediaCodecVideoDecoder(),
        fallback = MmrVideoDecoder(),
    )

// No ffmpeg-backed thumbnail decoder on Android: native MediaCodec → MediaMetadataRetriever is
// forgiving enough for the codec mix we ship for. The FFmpegKit AAR is already on the Android
// classpath (compression uses it), so dropping in an `FFmpegKitVideoDecoder` here is essentially
// free in APK terms if real-world data later shows codecs the native path refuses.
internal actual fun platformFfmpegDecoder(): VideoDecoder? = null
