package id.homebase.api.video

internal actual fun platformVideoDecoder(): VideoDecoder = AndroidNativeVideoDecoder()

// No ffmpeg-backed thumbnail decoder on Android: the native MediaCodec → MediaMetadataRetriever
// tiering inside [AndroidNativeVideoDecoder] is forgiving enough for the codec mix we see in the
// wild, and `MediaMetadataRetriever` is itself a hardware-backed native fallback. The FFmpegKit
// AAR is already on the Android classpath (compression uses it), so adding an Android
// `FFmpegKitVideoDecoder` for thumbnails is essentially free in APK terms if real-world data
// later shows codecs the native path refuses — drop a class in and switch this to non-null.
internal actual fun platformFfmpegDecoder(): VideoDecoder? = null
