package id.homebase.api.video

/**
 * Cheap read-only metadata queries over a video file, behind an interface so
 * callers (e.g. [VideoPayloadProcessor]) can be unit-tested with a fake.
 *
 * Only the probes with a real common-code consumer live here. `getRotationFromFile`
 * is deliberately *not* exposed — it has no common caller and is used only inside
 * the per-platform compress actuals to feed [FfmpegCompressPlanner]; it stays on
 * [FFmpegUtils].
 *
 * The production implementation is [FFmpegVideoProber], reached through
 * [VideoCompressionService].
 *
 * Named `VideoProber` (the actor) rather than `VideoProbe` to avoid colliding with the
 * web-only `FFmpegBridge.VideoProbe` probe-*result* data class on the wasmJs target (both
 * would land at the same FQN in the commonMain + wasmJsMain compilation).
 */
interface VideoProber {
    /** Duration of [inputPath] in milliseconds, or a non-positive value if unknown. */
    suspend fun getDurationMs(inputPath: String): Long

    /**
     * Version reported by the underlying ffmpeg (e.g. "n6.0", "6.1.1"), or null if
     * it could not be determined.
     */
    suspend fun getFfmpegVersion(): String?

    /**
     * Probe the first video track of [inputPath] for real codec/resolution/bit-depth/HDR,
     * or null if it can't be probed. Cheap (metadata only), so [VideoCompressionService]
     * leaves it outside the heavy-op lock.
     */
    suspend fun probeVideo(inputPath: String): VideoTrackInfo?
}
