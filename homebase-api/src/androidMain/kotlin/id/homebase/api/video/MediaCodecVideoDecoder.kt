package id.homebase.api.video

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import android.util.Size
import androidx.core.net.toUri
import id.homebase.api.ActivityProvider
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Hardware-accelerated Android primary. Poster path goes through `loadThumbnail` first on
 * Android 10+ for content URIs (the system thumbnail cache when present is near-instant);
 * strip path drives `VideoThumbnailsMediaCodec` for a single decoder warm-pass over the
 * sample-walked keyframes.
 *
 * Composed with [MmrVideoDecoder] via [TieredVideoDecoder] in the factory: when MediaCodec
 * fails outright or produces a partial strip, the tier-runner falls through to MMR to fill
 * the gap. Posters that `loadThumbnail` can't satisfy (pre-Q, file paths, unsupported
 * providers) fall through to MMR as well.
 */
class MediaCodecVideoDecoder : VideoDecoder {

    override suspend fun extractPosterFrame(videoPath: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val context = ActivityProvider.requireApplicationContext()
            when (val s = videoPath.toAndroidVideoSource()) {
                is AndroidVideoSource.ContentUri -> tryLoadThumbnail(context, s.uriString)
                // No fast native path for file paths — let the MMR tier handle it.
                is AndroidVideoSource.Path -> null
            }
        }

    override fun extractThumbnailStrip(
        videoPath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
    ): Flow<IndexedFrame> = channelFlow {
        if (frameCount <= 0 || durationMs <= 0L) return@channelFlow

        val context = ActivityProvider.requireApplicationContext()
        val source = videoPath.toAndroidVideoSource()

        val codecSource: VideoThumbnailsMediaCodec.Source = when (source) {
            is AndroidVideoSource.ContentUri ->
                VideoThumbnailsMediaCodec.Source.ContentUri(context, source.uriString.toUri())
            is AndroidVideoSource.Path -> VideoThumbnailsMediaCodec.Source.Path(source.path)
        }

        // Even spacing over [0, duration], biased to mid-step so the first frame isn't always
        // at t=0 (which is sometimes a black/blank frame on captured videos). The PTS the
        // codec returns is informational — we report wall-clock millis tied to the requested
        // slot, which is what the trim scrubber displays.
        val step = durationMs.toDouble() / frameCount
        fun timeForIndex(i: Int): Long = (step * (i + 0.5)).toLong().coerceIn(0L, durationMs - 1)

        VideoThumbnailsMediaCodec.extract(
            source = codecSource,
            count = frameCount,
            targetHeightPx = targetHeightPx,
            callback = object : VideoThumbnailsMediaCodec.Callback {
                override fun onFrame(index: Int, presentationTimeMs: Long, bitmap: Bitmap): Boolean {
                    if (!isActive) return false
                    val jpeg = bitmap.toJpegBytes(VideoThumbnailQuality.STRIP_JPEG_QUALITY_0_TO_100)
                    bitmap.recycle()
                    val ok = trySend(IndexedFrame(index, timeForIndex(index), jpeg)).isSuccess
                    return ok && isActive
                }

                override fun onFailed(t: Throwable) {
                    Log.w(TAG, "MediaCodec strip failed for $source", t)
                }
            },
        )
    }.flowOn(Dispatchers.IO)

    private fun tryLoadThumbnail(context: Context, contentUri: String): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val bitmap = context.contentResolver.loadThumbnail(
                contentUri.toUri(),
                Size(TARGET_PX, TARGET_PX),
                CancellationSignal(),
            )
            bitmap.toJpegBytes(VideoThumbnailQuality.POSTER_JPEG_QUALITY_0_TO_100).also { bitmap.recycle() }
        } catch (e: Exception) {
            Log.d(TAG, "loadThumbnail failed for $contentUri: ${e.message}")
            null
        }
    }

    private fun Bitmap.toJpegBytes(quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "MediaCodecVideoDecoder"
        private const val TARGET_PX = 640
    }
}
