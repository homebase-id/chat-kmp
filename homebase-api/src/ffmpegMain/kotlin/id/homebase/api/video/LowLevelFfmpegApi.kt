package id.homebase.api.video

/**
 * Marks the low-level, platform-dispatched ffmpeg backend ([FFmpegUtils]) as an
 * opt-in API.
 *
 * Production code should go through the generic seams instead:
 * - **compression / segmentation / probes** → [VideoCompressionService]
 *   ([VideoCompressor] + [VideoProber]);
 * - **thumbnails / poster frames / filmstrips** → [VideoThumbnailService].
 *
 * Calling [FFmpegUtils] directly is reserved for:
 * - the seam implementations themselves ([FFmpegVideoCompressor], [FFmpegVideoProber],
 *   and the thumbnail decoders) that delegate to it;
 * - the small set of helpers with no seam equivalent (`getRotationFromFile`,
 *   `getUniqueId`, `generateHlsKeyInfoFile`);
 * - backend integration tests that deliberately exercise the real ffmpeg per platform.
 *
 * Each such site opts in explicitly with `@OptIn(LowLevelFfmpegApi::class)`, so any *new,
 * accidental* direct use of `FFmpegUtils` is a compile error until the author consciously
 * acknowledges it — keeping the generic layer the default doorway.
 */
@RequiresOptIn(
    message = "Low-level ffmpeg backend. Prefer VideoCompressionService / VideoThumbnailService; " +
        "opt in only for the seam implementations or backend integration tests.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
annotation class LowLevelFfmpegApi
