package id.homebase.api.video

// Android has no ffmpeg thumbnail decoder (see [AndroidVideoDecoderFactory]) and host tests
// can't reliably exercise MediaCodec / MediaMetadataRetriever without an Android runtime —
// the common test will short-circuit when `ffmpegDecoderForTest` is null anyway.
internal actual suspend fun stageSampleVideoForFfmpegTest(): String? = null

internal actual suspend fun cleanupStagedSampleVideo(path: String) {
    // no-op
}
