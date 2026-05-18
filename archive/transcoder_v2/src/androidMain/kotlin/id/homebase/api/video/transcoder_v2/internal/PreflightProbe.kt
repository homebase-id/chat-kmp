package id.homebase.api.video.transcoder_v2.internal

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Cheap one-shot probe of the input file: gives us enough to make the
 * already-optimal decision (SPEC §7) without spinning up the full
 * extractor pipeline. Falls back to "can't probe → re-encode" on any
 * exception (SPEC §7 "missing duration metadata" edge case).
 */
internal object PreflightProbe {

    private const val TAG = "PreflightProbe"

    data class Probe(
        val videoCodec: String?,
        val videoTrackCount: Int,
        val audioTrackCount: Int,
        val widthPx: Int,
        val heightPx: Int,
        val rotationDeg: Int,
        val durationMs: Long,
        val fileSizeBytes: Long,
        val isHdr: Boolean,
    ) {
        val shortEdgePx: Int get() = minOf(widthPx, heightPx)
        val computedBitrateBps: Long get() =
            if (durationMs > 0) fileSizeBytes * 8L * 1000L / durationMs else 0L
    }

    fun probe(inputPath: String): Probe? {
        val file = File(inputPath)
        if (!file.exists()) return null

        var videoTrackCount = 0
        var audioTrackCount = 0
        var videoCodec: String? = null
        var widthPx = 0
        var heightPx = 0
        var isHdr = false

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputPath)
            val n = extractor.trackCount
            for (i in 0 until n) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") -> {
                        videoTrackCount++
                        if (videoCodec == null) {
                            videoCodec = mime
                            widthPx = readDimension(format, MediaFormat.KEY_WIDTH, "display-width")
                            heightPx = readDimension(format, MediaFormat.KEY_HEIGHT, "display-height")
                            isHdr = isHdrVideo(format)
                        }
                    }
                    mime.startsWith("audio/") -> audioTrackCount++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaExtractor probe failed for $inputPath", e)
            return null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }

        if (videoCodec == null) return null

        val retriever = MediaMetadataRetriever()
        var rotation = 0
        var duration = 0L
        try {
            retriever.setDataSource(inputPath)
            rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever probe failed for $inputPath", e)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        return Probe(
            videoCodec = videoCodec,
            videoTrackCount = videoTrackCount,
            audioTrackCount = audioTrackCount,
            widthPx = widthPx,
            heightPx = heightPx,
            rotationDeg = rotation,
            durationMs = duration,
            fileSizeBytes = file.length(),
            isHdr = isHdr,
        )
    }

    /**
     * SPEC §7. Skip transcode iff ALL: trim is null (caller filters),
     * single video track, ≤1 audio track, H.264 codec, short edge fits,
     * bitrate within 1.2× envelope.
     */
    fun isAlreadyOptimal(probe: Probe, targetVideoBps: Int, targetAudioBps: Int, targetShortEdgePx: Int): Boolean {
        if (probe.videoTrackCount != 1) return false
        if (probe.audioTrackCount > 1) return false
        if (!isH264(probe.videoCodec)) return false
        if (probe.shortEdgePx <= 0) return false
        if (probe.shortEdgePx > targetShortEdgePx) return false
        if (probe.durationMs <= 0) return false  // can't probe → re-encode
        val budget = (1.2 * (targetVideoBps + targetAudioBps)).toLong()
        if (probe.computedBitrateBps > budget) return false
        return true
    }

    private fun isH264(mime: String?): Boolean =
        mime != null && mime.equals("video/avc", ignoreCase = true)

    private fun readDimension(format: MediaFormat, primaryKey: String, displayKey: String): Int {
        // SPEC §9.5: prefer display dimension where available.
        return try {
            if (format.containsKey(displayKey)) format.getInteger(displayKey)
            else format.getInteger(primaryKey)
        } catch (_: Exception) {
            0
        }
    }

    /** SPEC §11 + signal-reference-notes MediaCodecCompat:249-285. */
    fun isHdrVideo(format: MediaFormat): Boolean {
        try {
            val colorTransfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
            if (colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                colorTransfer == MediaFormat.COLOR_TRANSFER_HLG
            ) return true
            // Treat non-standard values as HDR (some devices report 65791 etc.)
            if (colorTransfer != 0 &&
                colorTransfer != MediaFormat.COLOR_TRANSFER_LINEAR &&
                colorTransfer != MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            ) return true
        } catch (_: NullPointerException) {
        } catch (_: ClassCastException) {
        }

        if (format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) return true
        if (Build.VERSION.SDK_INT >= 29 && format.containsKey(MediaFormat.KEY_HDR10_PLUS_INFO)) return true

        val mime = try { format.getString(MediaFormat.KEY_MIME) } catch (_: Exception) { null }
        if (mime != null && mime.equals("video/hevc", ignoreCase = true)) {
            try {
                val profile = format.getInteger(MediaFormat.KEY_PROFILE)
                if (profile == CodecProfileLevel.HEVCProfileMain10HDR10 ||
                    profile == CodecProfileLevel.HEVCProfileMain10HDR10Plus
                ) return true
            } catch (_: Exception) {
            }
        }
        return false
    }
}
