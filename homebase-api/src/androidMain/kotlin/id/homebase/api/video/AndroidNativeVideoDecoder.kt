package id.homebase.api.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import android.util.Size
import androidx.core.net.toUri
import id.homebase.api.ActivityProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Native Android decoder. Handles both filesystem paths and `content://` URIs (gallery picks /
 * share sheet) — no copy-to-cache is needed because `MediaMetadataRetriever` and `MediaCodec`
 * both accept `(Context, Uri)` directly. Internally tiered for the strip path:
 * `VideoThumbnailsMediaCodec` (single-pass hardware decode) → `MediaMetadataRetriever`
 * (per-frame fallback) when MediaCodec refuses the stream or stops short.
 *
 * No FFmpeg tier on Android — both native options together already cover the codec mix we see in
 * the wild. If that ever changes, layer an FFmpeg decoder in via [TieredVideoDecoder].
 */
internal class AndroidNativeVideoDecoder : VideoDecoder {

    override suspend fun extractPosterFrame(videoPath: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val context = ActivityProvider.requireApplicationContext()
            when (val s = videoPath.toAndroidVideoSource()) {
                is AndroidVideoSource.ContentUri -> {
                    tryLoadThumbnail(context, s.uriString)?.let { return@withContext it }
                    tryMediaMetadataRetrieverForUri(context, s.uriString)
                }
                is AndroidVideoSource.Path -> tryMediaMetadataRetrieverForPath(s.path)
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
        val androidSource = videoPath.toAndroidVideoSource()
        if (androidSource is AndroidVideoSource.Path && !File(androidSource.path).exists()) {
            return@channelFlow
        }

        val codecSource: VideoThumbnailsMediaCodec.Source = when (androidSource) {
            is AndroidVideoSource.ContentUri ->
                VideoThumbnailsMediaCodec.Source.ContentUri(context, androidSource.uriString.toUri())
            is AndroidVideoSource.Path -> VideoThumbnailsMediaCodec.Source.Path(androidSource.path)
        }

        // Even spacing over [0, duration], biased to mid-step so the first frame isn't always
        // at t=0 (which is sometimes a black/blank frame on captured videos). MediaCodec gives
        // us PTS in microseconds on the BufferInfo, but we report wall-clock millis tied to the
        // requested slot — that's what VideoTrimScrubber displays.
        val step = durationMs.toDouble() / frameCount
        fun timeForIndex(i: Int): Long = (step * (i + 0.5)).toLong().coerceIn(0L, durationMs - 1)

        // Tier 1: MediaCodec single-pass. Cancellation via channel close → cb returns false.
        var emitted = 0
        var lastFailure: Throwable? = null
        VideoThumbnailsMediaCodec.extract(
            source = codecSource,
            count = frameCount,
            targetHeightPx = targetHeightPx,
            callback = object : VideoThumbnailsMediaCodec.Callback {
                override fun onFrame(index: Int, presentationTimeMs: Long, bitmap: Bitmap): Boolean {
                    if (!isActive) return false
                    val jpeg = bitmap.toJpegBytes(STRIP_JPEG_QUALITY)
                    bitmap.recycle()
                    val ok = trySend(IndexedFrame(index, timeForIndex(index), jpeg)).isSuccess
                    if (ok) emitted++
                    return ok && isActive
                }

                override fun onFailed(t: Throwable) {
                    lastFailure = t
                }
            },
        )

        if (emitted >= frameCount || !isActive) return@channelFlow

        // Tier 2: MediaMetadataRetriever fallback. Triggered when MediaCodec produced nothing
        // (codec refused the stream / threw) or stopped short. Skip any indices we already
        // emitted on tier 1 so the scrubber doesn't see duplicates.
        if (lastFailure != null) {
            Log.w(TAG, "MediaCodec path failed; falling back to MMR for $androidSource", lastFailure)
        } else if (emitted in 1 until frameCount) {
            Log.w(TAG, "MediaCodec path produced only $emitted/$frameCount frames; filling gaps via MMR")
        }
        extractStripViaMmr(
            context = context,
            source = androidSource,
            durationMs = durationMs,
            frameCount = frameCount,
            targetHeightPx = targetHeightPx,
            startFromIndex = emitted,
        )
    }.flowOn(Dispatchers.IO)

    private suspend fun ProducerScope<IndexedFrame>.extractStripViaMmr(
        context: Context,
        source: AndroidVideoSource,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
        startFromIndex: Int,
    ) {
        val retriever = MediaMetadataRetriever()
        try {
            when (source) {
                is AndroidVideoSource.ContentUri -> retriever.setDataSource(context, source.uriString.toUri())
                is AndroidVideoSource.Path -> retriever.setDataSource(source.path)
            }

            val step = durationMs.toDouble() / frameCount
            for (i in startFromIndex until frameCount) {
                if (!isActive) break
                val targetMs = (step * (i + 0.5)).toLong().coerceIn(0L, durationMs - 1)
                val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        targetMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetHeightPx * 4,
                        targetHeightPx,
                    )
                } else {
                    retriever.getFrameAtTime(
                        targetMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )?.let { full ->
                        val ratio = targetHeightPx.toFloat() / full.height
                        Bitmap.createScaledBitmap(
                            full,
                            (full.width * ratio).toInt().coerceAtLeast(1),
                            targetHeightPx,
                            true,
                        ).also { if (it !== full) full.recycle() }
                    }
                }

                if (bitmap != null) {
                    val jpeg = bitmap.toJpegBytes(STRIP_JPEG_QUALITY)
                    bitmap.recycle()
                    trySend(IndexedFrame(i, targetMs, jpeg))
                } else {
                    Log.d(TAG, "MMR strip frame $i @${targetMs}ms — null")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MMR fallback failed for $source", e)
        } finally {
            retriever.runCatching { release() }
        }
    }

    private fun tryLoadThumbnail(context: Context, contentUri: String): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val bitmap = context.contentResolver.loadThumbnail(
                contentUri.toUri(),
                Size(TARGET_PX, TARGET_PX),
                CancellationSignal(),
            )
            bitmap.toJpegBytes(POSTER_JPEG_QUALITY).also { bitmap.recycle() }
        } catch (e: Exception) {
            Log.d(TAG, "loadThumbnail failed for $contentUri: ${e.message}")
            null
        }
    }

    private fun tryMediaMetadataRetrieverForUri(context: Context, contentUri: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, contentUri.toUri())
            retriever.frameAtZero()?.let { it.toJpegBytes(POSTER_JPEG_QUALITY).also { _ -> it.recycle() } }
        } catch (e: Exception) {
            Log.d(TAG, "MMR(uri) failed for $contentUri: ${e.message}")
            null
        } finally {
            retriever.runCatching { release() }
        }
    }

    private fun tryMediaMetadataRetrieverForPath(filePath: String): ByteArray? {
        val file = File(filePath)
        if (!file.exists()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            retriever.frameAtZero()?.let { it.toJpegBytes(POSTER_JPEG_QUALITY).also { _ -> it.recycle() } }
        } catch (e: Exception) {
            Log.d(TAG, "MMR(path) failed for $filePath: ${e.message}")
            null
        } finally {
            retriever.runCatching { release() }
        }
    }

    private fun MediaMetadataRetriever.frameAtZero(): Bitmap? =
        getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

    private fun Bitmap.toJpegBytes(quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "AndroidNativeVideoDecoder"
        private const val TARGET_PX = 640
        private const val POSTER_JPEG_QUALITY = 80
        private const val STRIP_JPEG_QUALITY = 60
    }
}

