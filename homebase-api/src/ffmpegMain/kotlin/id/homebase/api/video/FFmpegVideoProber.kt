@file:OptIn(LowLevelFfmpegApi::class) // thin adapter over the FFmpegUtils backend by design

package id.homebase.api.video

/**
 * ffmpeg-backed [VideoProber]. Thin adapter over [FFmpegUtils];
 * stateless, hence an `object`.
 */
internal object FFmpegVideoProber : VideoProber {

    override suspend fun getDurationMs(inputPath: String): Long =
        FFmpegUtils.getDurationMs(inputPath)

    override suspend fun getFfmpegVersion(): String? =
        FFmpegUtils.getFfmpegVersion()

    override suspend fun probeVideo(inputPath: String): VideoTrackInfo? =
        FFmpegUtils.probeVideo(inputPath)

    override fun canRead(contentType: String): Boolean = contentType.startsWith("video/")
}
