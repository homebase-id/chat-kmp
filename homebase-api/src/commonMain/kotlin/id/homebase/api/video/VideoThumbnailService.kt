package id.homebase.api.video

import kotlinx.coroutines.flow.Flow

/**
 * Singleton entry point for poster-frame and trim-scrubber filmstrip extraction.
 *
 * Each platform assembles its [VideoDecoder] via [platformVideoDecoder]:
 *
 * | Platform | Pipeline                                                                              |
 * |----------|---------------------------------------------------------------------------------------|
 * | Android  | `loadThumbnail` / `MediaMetadataRetriever` for posters; `MediaCodec` → MMR for strips |
 * | iOS      | `AVAssetImageGenerator` first, FFmpegKit fallback                                     |
 * | Desktop  | `ffmpeg` subprocess (no OS-native decoder available)                                  |
 * | Web      | `<video>`+`<canvas>` first, ffmpeg.wasm fallback                                      |
 *
 * `videoPath` is platform-specific. On Android it may be a real filesystem path OR a
 * `content://` URI (only Android has Storage Access Framework). On iOS / Desktop it is a real
 * filesystem path. On Web it is an okio path into the in-memory FakeFileSystem the upload
 * pipeline materializes browser-picked bytes into. The contract stays a `String` (not a
 * `PlatformFile`) because the okio web case isn't expressible as a FileKit `PlatformFile` — and
 * because the rest of the video pipeline (`FFmpegUtils.*`) is already string-based.
 *
 * Returns JPEG bytes so callers don't need to dance through a temp file plus delete. The strip
 * is a [Flow] so the UI fills progressively.
 */
object VideoThumbnailService : VideoDecoder {
    private val delegate: VideoDecoder by lazy { platformVideoDecoder() }

    /**
     * Test-only handle on the ffmpeg backend for the current platform. Bypasses the native
     * primary, so a test can pass an ordinary video and exercise the ffmpeg code path without
     * needing to find a codec the native primary refuses.
     *
     * Per platform: JVM → `ffmpeg` subprocess; iOS → FFmpegKit; Web → ffmpeg.wasm;
     * Android → `null` (this codebase has no ffmpeg on Android — its fallback is the native
     * MediaMetadataRetriever).
     *
     * Don't reach for this in production code — go through the regular [extractPosterFrame] /
     * [extractThumbnailStrip] tier-runner, which picks the right decoder for the platform.
     */
    val ffmpegDecoderForTest: VideoDecoder? by lazy { platformFfmpegDecoder() }

    override suspend fun extractPosterFrame(videoPath: String): ByteArray? =
        delegate.extractPosterFrame(videoPath)

    override fun extractThumbnailStrip(
        videoPath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
    ): Flow<IndexedFrame> =
        delegate.extractThumbnailStrip(videoPath, durationMs, frameCount, targetHeightPx)
}

/** Per-platform factory: builds the [VideoDecoder] used by [VideoThumbnailService]. */
internal expect fun platformVideoDecoder(): VideoDecoder

/** Per-platform factory: returns the ffmpeg backend for tests, or null if none exists. */
internal expect fun platformFfmpegDecoder(): VideoDecoder?
