package id.homebase.api.video

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Runs [primary] first; falls back to [fallback] only on failure (poster) or to fill missing
 * indices (strip). The fallback itself is unaware of which indices the primary filled — it just
 * emits its own N frames and this runner drops the ones whose index was already produced.
 *
 * Used by the iOS and Web factories. Android composes the same way (MediaCodec primary,
 * MediaMetadataRetriever fallback). JVM has no useful primary in front of ffmpeg, so its
 * decoder isn't wrapped at all.
 *
 * **Cancellation discipline.** We catch `Throwable` from the primary so a codec failure flows
 * into the fallback — but `CancellationException` is rethrown unchanged so cooperative
 * cancellation from the calling scope still tears everything down. The same project foot-gun is
 * called out at `AttachmentHandler.kt:167-171` for `runCatching`; using explicit `try/catch`
 * here keeps cancellation correct.
 */
internal class TieredVideoDecoder(
    private val primary: VideoDecoder,
    private val fallback: VideoDecoder?,
) : VideoDecoder {

    override suspend fun extractPosterFrame(videoPath: String): ByteArray? {
        val first = try {
            primary.extractPosterFrame(videoPath)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
        if (first != null) return first
        return fallback?.extractPosterFrame(videoPath)
    }

    override fun extractThumbnailStrip(
        videoPath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
        skipMask: BooleanArray?,
    ): Flow<IndexedFrame> = channelFlow {
        if (frameCount <= 0 || durationMs <= 0L) return@channelFlow
        val emitted = BooleanArray(frameCount)
        // Carry a caller-supplied skipMask through into our own emitted bookkeeping so a
        // chained TieredVideoDecoder (nested fallback) doesn't re-decode work already done
        // further up. The primary itself can ignore skipMask if it wants (today's primaries
        // do — see their class comments), but we pass it through so the contract is "every
        // decoder receives the same mask the runner sees."
        if (skipMask != null) {
            for (i in 0 until frameCount) {
                if (i < skipMask.size && skipMask[i]) emitted[i] = true
            }
        }
        var emittedCount = emitted.count { it }

        try {
            primary.extractThumbnailStrip(
                videoPath = videoPath,
                durationMs = durationMs,
                frameCount = frameCount,
                targetHeightPx = targetHeightPx,
                skipMask = if (skipMask != null) emitted.copyOf() else null,
            ).collect { f ->
                if (f.index in 0 until frameCount && !emitted[f.index]) {
                    emitted[f.index] = true
                    emittedCount++
                    trySend(f)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Primary errored — fallback will take over below.
        }

        if (emittedCount >= frameCount) return@channelFlow
        val fb = fallback ?: return@channelFlow

        // Hand the fallback a SNAPSHOT of which indices we've already produced. Decoders that
        // can act on this (today only MmrVideoDecoder on Android — see VideoDecoder.kt KDoc)
        // skip those decodes entirely. We copy so the fallback's iteration can't race the
        // collect lambda's mutation of `emitted`.
        fb.extractThumbnailStrip(
            videoPath = videoPath,
            durationMs = durationMs,
            frameCount = frameCount,
            targetHeightPx = targetHeightPx,
            skipMask = emitted.copyOf(),
        ).collect { f ->
            if (f.index in 0 until frameCount && !emitted[f.index]) {
                emitted[f.index] = true
                trySend(f)
            }
        }
    }
}
