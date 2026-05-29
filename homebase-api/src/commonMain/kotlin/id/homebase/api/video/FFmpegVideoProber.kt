package id.homebase.api.video

/**
 * ffmpeg-backed [VideoProber]. Thin `commonMain` adapter over [FFmpegUtils];
 * stateless, hence an `object`.
 */
internal object FFmpegVideoProber : VideoProber {

    override suspend fun getDurationMs(inputPath: String): Long =
        FFmpegUtils.getDurationMs(inputPath)

    override suspend fun getFfmpegVersion(): String? =
        FFmpegUtils.getFfmpegVersion()
}
