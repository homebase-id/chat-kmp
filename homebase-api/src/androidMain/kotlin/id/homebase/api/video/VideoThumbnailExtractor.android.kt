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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

actual object VideoThumbnailExtractor {
    private const val TAG = "VideoThumbnailExtractor"
    private const val TARGET_PX = 640
    private const val JPEG_QUALITY = 80

    actual suspend fun extractPosterFrame(videoPath: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val context = ActivityProvider.requireActivity().applicationContext
            val isContentUri = videoPath.startsWith("content://") || videoPath.startsWith("content:")

            if (isContentUri) {
                tryLoadThumbnail(context, videoPath)?.let { return@withContext it }
                tryMediaMetadataRetrieverForUri(context, videoPath)?.let { return@withContext it }
            } else {
                tryMediaMetadataRetrieverForPath(videoPath)?.let { return@withContext it }
            }

            null
        }

    private fun tryLoadThumbnail(context: Context, contentUri: String): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val bitmap = context.contentResolver.loadThumbnail(
                contentUri.toUri(),
                Size(TARGET_PX, TARGET_PX),
                CancellationSignal(),
            )
            bitmap.toJpegBytes().also { bitmap.recycle() }
        } catch (e: Exception) {
            Log.d(TAG, "loadThumbnail failed for $contentUri: ${e.message}")
            null
        }
    }

    private fun tryMediaMetadataRetrieverForUri(context: Context, contentUri: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, contentUri.toUri())
            retriever.frameAtZero()?.let { it.toJpegBytes().also { _ -> it.recycle() } }
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
            retriever.frameAtZero()?.let { it.toJpegBytes().also { _ -> it.recycle() } }
        } catch (e: Exception) {
            Log.d(TAG, "MMR(path) failed for $filePath: ${e.message}")
            null
        } finally {
            retriever.runCatching { release() }
        }
    }

    private fun MediaMetadataRetriever.frameAtZero(): Bitmap? =
        getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

    private fun Bitmap.toJpegBytes(quality: Int = JPEG_QUALITY): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private const val STRIP_JPEG_QUALITY = 60

    actual fun extractThumbnailStrip(
        filePath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
    ): Flow<IndexedFrame> = channelFlow {
        if (frameCount <= 0 || durationMs <= 0L) return@channelFlow

        val context = ActivityProvider.requireActivity().applicationContext
        val isContentUri = filePath.startsWith("content://") || filePath.startsWith("content:")

        // Open one MMR; it's safe to call getFrameAtTime sequentially on a single instance,
        // and the underlying decode is bottlenecked by the codec anyway. Parallel MMRs
        // tend to fight over the HW decoder pool.
        val retriever = MediaMetadataRetriever()
        try {
            if (isContentUri) {
                retriever.setDataSource(context, filePath.toUri())
            } else {
                if (!File(filePath).exists()) return@channelFlow
                retriever.setDataSource(filePath)
            }

            // Spread N samples evenly across [0, duration], biased to start at half-step
            // so the first frame isn't always at t=0 (which is sometimes the black/blank
            // frame on captured videos).
            val step = durationMs.toDouble() / frameCount
            for (i in 0 until frameCount) {
                if (!isActive) break
                val targetMs = (step * (i + 0.5)).toLong().coerceIn(0L, durationMs - 1)
                val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        targetMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        // width = -1 isn't supported; pass an aspect-preserving width by
                        // first measuring (cheap header read), or just pass a wide enough
                        // bounding width so getScaledFrameAtTime can scale to fit.
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
                    Log.d(TAG, "strip frame $i @${targetMs}ms — null")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractThumbnailStrip failed for $filePath", e)
        } finally {
            retriever.runCatching { release() }
        }
    }.flowOn(Dispatchers.IO)
}
