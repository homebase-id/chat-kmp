package id.homebase.api.video

// Android device tests also have no ffmpeg thumbnail decoder to exercise (see
// [AndroidVideoDecoderFactory]) — the common test short-circuits when
// `ffmpegDecoderForTest` is null. The native MediaCodec/MMR path is exercised by
// [CompressVideoAndroidInstrumentedTest] et al. instead.
internal actual suspend fun stageSampleVideoForFfmpegTest(): String? = null

internal actual suspend fun cleanupStagedSampleVideo(path: String) {
    // no-op
}
