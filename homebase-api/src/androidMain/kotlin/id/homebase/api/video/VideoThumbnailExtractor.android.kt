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

    private fun Bitmap.toJpegBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }
}
