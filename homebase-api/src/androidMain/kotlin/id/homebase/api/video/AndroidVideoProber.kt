package id.homebase.api.video

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import co.touchlab.kermit.Logger
import id.homebase.api.ActivityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun platformVideoProber(): VideoProber = AndroidVideoProber

/** The platform's own extractor and retriever: headers only, no pass over the media. */
internal object AndroidVideoProber : VideoProber {
    private const val TAG = "AndroidVideoProber"

    override suspend fun getDurationMs(inputPath: String): Long = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            when (val source = inputPath.toAndroidVideoSource()) {
                is AndroidVideoSource.ContentUri -> retriever.setDataSource(ActivityProvider.requireApplicationContext(), Uri.parse(source.uriString))
                is AndroidVideoSource.Path -> retriever.setDataSource(source.path)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "getDurationMs failed for $inputPath" }
            0L
        } finally {
            retriever.release()
        }
    }

    override suspend fun getFfmpegVersion(): String? = null

    // Bit depth and HDR stay null when the track doesn't say: that is "unknown", not 8-bit SDR.
    override suspend fun probeVideo(inputPath: String): VideoTrackInfo? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            when (val source = inputPath.toAndroidVideoSource()) {
                is AndroidVideoSource.ContentUri -> extractor.setDataSource(ActivityProvider.requireApplicationContext(), Uri.parse(source.uriString), null)
                is AndroidVideoSource.Path -> extractor.setDataSource(source.path)
            }
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return@withContext null
            VideoTrackInfo(
                format.getString(MediaFormat.KEY_MIME),
                format.dimension(MediaFormat.KEY_WIDTH, "display-width"),
                format.dimension(MediaFormat.KEY_HEIGHT, "display-height"),
                format.bitDepth(),
                format.isHdr(),
            )
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "probeVideo failed for $inputPath" }
            null
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.dimension(primary: String, display: String): Int =
        runCatching { if (containsKey(display)) getInteger(display) else getInteger(primary) }.getOrDefault(0)

    private fun MediaFormat.bitDepth(): Int? {
        if (!containsKey(MediaFormat.KEY_PROFILE)) return null
        val profile = runCatching { getInteger(MediaFormat.KEY_PROFILE) }.getOrNull() ?: return null
        return if (profile in TEN_BIT_PROFILES) 10 else 8
    }

    private fun MediaFormat.isHdr(): Boolean? {
        val hasPlus = Build.VERSION.SDK_INT >= 29 && containsKey(MediaFormat.KEY_HDR10_PLUS_INFO)
        if (containsKey(MediaFormat.KEY_HDR_STATIC_INFO) || hasPlus) return true
        if (!containsKey(MediaFormat.KEY_COLOR_TRANSFER)) return null
        val transfer = runCatching { getInteger(MediaFormat.KEY_COLOR_TRANSFER) }.getOrNull() ?: return null
        return transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
    }

    private val TEN_BIT_PROFILES = setOf(
        CodecProfileLevel.AVCProfileHigh10,
        CodecProfileLevel.HEVCProfileMain10,
        CodecProfileLevel.HEVCProfileMain10HDR10,
        CodecProfileLevel.HEVCProfileMain10HDR10Plus,
        CodecProfileLevel.VP9Profile2,
        CodecProfileLevel.VP9Profile3,
        CodecProfileLevel.VP9Profile2HDR,
        CodecProfileLevel.VP9Profile3HDR,
    )
}
