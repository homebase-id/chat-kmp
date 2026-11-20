package id.homebase.api.video

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import id.homebase.api.ActivityProvider
import id.homebase.imageresize.NativeResize
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * `MediaMetadataRetriever`-driven decoder for Android. Per-frame `getScaledFrameAtTime`
 * decoding — slower than [MediaCodecVideoDecoder] but more forgiving on weird containers, so
 * it serves as the fallback tier and also as the poster path when `loadThumbnail` isn't
 * applicable (pre-Q, non-content URIs, or when the platform API returns null).
 *
 * Independently usable: the test API at `VideoThumbnailService.ffmpegDecoderForTest` doesn't
 * surface it (Android has no ffmpeg), but Android instrumented tests can instantiate it
 * directly to validate the MMR path against the existing
 * `androidDeviceTest/assets/test_videos/sample.mp4` fixture.
 */
internal class MmrVideoDecoder : VideoDecoder {

    override suspend fun extractPosterFrame(videoPath: String, fullSize: Boolean): ByteArray? =
        withContext(Dispatchers.IO) {
            val context = ActivityProvider.requireApplicationContext()
            when (val s = videoPath.toAndroidVideoSource()) {
                is AndroidVideoSource.ContentUri -> tryForUri(context, s.uriString, fullSize)
                is AndroidVideoSource.Path -> tryForPath(s.path, fullSize)
            }
        }

    override fun extractThumbnailStrip(
        videoPath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
        skipMask: BooleanArray?,
    ): Flow<IndexedFrame> = channelFlow {
        if (frameCount <= 0 || durationMs <= 0L) return@channelFlow

        val context = ActivityProvider.requireApplicationContext()
        val source = videoPath.toAndroidVideoSource()
        if (source is AndroidVideoSource.Path && !File(source.path).exists()) {
            return@channelFlow
        }

        val retriever = MediaMetadataRetriever()
        try {
            when (source) {
                is AndroidVideoSource.ContentUri -> retriever.setDataSource(context, source.uriString.toUri())
                is AndroidVideoSource.Path -> retriever.setDataSource(source.path)
            }

            val step = durationMs.toDouble() / frameCount
            for (i in 0 until frameCount) {
                if (!isActive) break
                // Skip indices an earlier tier already produced — the tier-runner would drop
                // their duplicates anyway, but each MMR `getScaledFrameAtTime` is ~10-30 ms on
                // hardware, so honoring the mask saves the wasted decode entirely. See the
                // KDoc on VideoDecoder.extractThumbnailStrip for why other decoders ignore it.
                if (skipMask != null && i < skipMask.size && skipMask[i]) continue
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
                    val jpeg = bitmap.toJpegBytes(VideoThumbnailQuality.STRIP_JPEG_QUALITY_0_TO_100)
                    bitmap.recycle()
                    trySend(IndexedFrame(i, targetMs, jpeg))
                } else {
                    Log.d(TAG, "MMR strip frame $i @${targetMs}ms — null")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MMR strip failed for $source", e)
        } finally {
            retriever.runCatching { release() }
        }
    }.flowOn(Dispatchers.IO)

    private fun tryForUri(context: android.content.Context, contentUri: String, fullSize: Boolean): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, contentUri.toUri())
            retriever.posterJpeg(fullSize)
        } catch (e: Exception) {
            Log.d(TAG, "MMR(uri) failed for $contentUri: ${e.message}")
            null
        } finally {
            retriever.runCatching { release() }
        }
    }

    private fun tryForPath(filePath: String, fullSize: Boolean): ByteArray? {
        val file = File(filePath)
        if (!file.exists()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            retriever.posterJpeg(fullSize)
        } catch (e: Exception) {
            Log.d(TAG, "MMR(path) failed for $filePath: ${e.message}")
            null
        } finally {
            retriever.runCatching { release() }
        }
    }

    private fun MediaMetadataRetriever.posterJpeg(fullSize: Boolean): ByteArray? {
        val quality = VideoThumbnailQuality.posterJpegQuality0To100(fullSize)
        val frame = frameAtZero() ?: return null
        val poster = if (fullSize) frame.shrunkForUpload() else frame
        return poster.toJpegBytes(quality).also { poster.recycle() }
    }

    private fun MediaMetadataRetriever.frameAtZero(): Bitmap? =
        getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

    // getScaledFrameAtTime builds the full-size Bitmap too, then scales it bilinearly, so Lanczos here instead.
    private fun Bitmap.shrunkForUpload(): Bitmap {
        val longEdge = maxOf(width, height)
        val target = VideoThumbnailQuality.fullPosterLongEdge(width, height)
        if (target >= longEdge) return this
        val out = Bitmap.createBitmap(
            (width.toLong() * target / longEdge).toInt().coerceAtLeast(1),
            (height.toLong() * target / longEdge).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        try {
            NativeResize.lanczos(if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false), out)
        } catch (e: Throwable) {
            out.recycle()
            throw e
        } finally {
            recycle()
        }
        return out
    }

    private fun Bitmap.toJpegBytes(quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "MmrVideoDecoder"
    }
}
