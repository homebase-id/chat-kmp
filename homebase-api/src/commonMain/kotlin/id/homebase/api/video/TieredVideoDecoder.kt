package id.homebase.api.video

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Runs [primary] first; falls back to [fallback] only on failure (poster) or to fill missing
 * indices (strip). The fallback itself is unaware of which indices the primary filled — it just
 * emits its own N frames and this runner drops the ones whose index was already produced.
 *
 * Used by the iOS and Web factories. Android keeps an internal native-only two-tier path
 * (MediaCodec → MediaMetadataRetriever) because the second tier can target arbitrary missing
 * indices directly without re-decoding the whole strip. JVM has no useful primary in front of
 * ffmpeg, so its decoder isn't wrapped at all.
 */
class TieredVideoDecoder(
    private val primary: VideoDecoder,
    private val fallback: VideoDecoder?,
) : VideoDecoder {

    override suspend fun extractPosterFrame(videoPath: String): ByteArray? {
        val first = runCatching { primary.extractPosterFrame(videoPath) }.getOrNull()
        if (first != null) return first
        return fallback?.extractPosterFrame(videoPath)
    }

    override fun extractThumbnailStrip(
        videoPath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
    ): Flow<IndexedFrame> = channelFlow {
        if (frameCount <= 0 || durationMs <= 0L) return@channelFlow
        val emitted = BooleanArray(frameCount)

        runCatching {
            primary.extractThumbnailStrip(videoPath, durationMs, frameCount, targetHeightPx)
                .collect { f ->
                    if (f.index in 0 until frameCount && !emitted[f.index]) {
                        emitted[f.index] = true
                        trySend(f)
                    }
                }
        }

        if (emitted.all { it }) return@channelFlow
        val fb = fallback ?: return@channelFlow

        fb.extractThumbnailStrip(videoPath, durationMs, frameCount, targetHeightPx)
            .collect { f ->
                if (f.index in 0 until frameCount && !emitted[f.index]) {
                    emitted[f.index] = true
                    trySend(f)
                }
            }
    }
}
