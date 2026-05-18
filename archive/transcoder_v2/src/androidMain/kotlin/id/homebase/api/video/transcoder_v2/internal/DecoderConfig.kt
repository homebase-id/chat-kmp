package id.homebase.api.video.transcoder_v2.internal

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import id.homebase.api.video.transcoder_v2.CodecUnavailableException
import id.homebase.api.video.transcoder_v2.HdrDecoderUnavailableException
import java.util.Locale

/**
 * Creates a video decoder for [inputFormat], trying each candidate in
 * order with the mid-stream-retry [excludedNames] excluded. Implements
 * the HDR tone-map dance from SPEC §11 / signal-reference-notes
 * VideoTrackConverter:490-564.
 *
 * Returns the decoder + the recorded codec name + the resolved
 * `isToneMapApplied` flag. Caller owns the decoder lifecycle.
 */
internal object DecoderConfig {

    private const val TAG = "DecoderConfig"

    data class Configured(
        val decoder: MediaCodec,
        val codecName: String,
        val isHdrInput: Boolean,
        val isToneMapApplied: Boolean,
    )

    fun createVideoDecoder(
        inputFormat: MediaFormat,
        outputSurface: Surface?,
        excludedNames: Set<String>,
    ): Configured {
        val isHdr = PreflightProbe.isHdrVideo(inputFormat)
        val requestToneMapping = Build.VERSION.SDK_INT >= 31 && isHdr
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: throw CodecUnavailableException(codecMimeType = "unknown", isEncoder = false)

        val candidates = CodecSelection.selectDecoders(mime, excludedNames)
        if (candidates.isEmpty()) {
            if (isHdr) throw HdrDecoderUnavailableException("No decoder available for HDR $mime")
            throw CodecUnavailableException(codecMimeType = mime, isEncoder = false)
        }

        var lastError: Throwable? = null
        for ((i, info) in candidates.withIndex()) {
            val codecName = info.name
            var decoder: MediaCodec? = null
            try {
                decoder = MediaCodec.createByCodecName(codecName)

                if (requestToneMapping) {
                    try {
                        val toneMap = MediaFormat()
                        copyFormat(inputFormat, toneMap)
                        toneMap.setInteger(
                            MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                            MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                        )
                        decoder.configure(toneMap, outputSurface, null, 0)
                        decoder.start()
                        val effective = isToneMapEffective(decoder, codecName)
                        if (!effective) {
                            Log.w(TAG, "tone-map not effective on $codecName; trying next candidate (attempt ${i + 1}/${candidates.size})")
                            try { decoder.stop() } catch (_: Exception) {}
                            decoder.release()
                            decoder = null
                            continue
                        }
                        if (i > 0) Log.w(TAG, "decoder OK with fallback $codecName (attempt ${i + 1}/${candidates.size})")
                        return Configured(decoder, codecName, isHdr, isToneMapApplied = true)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "decoder $codecName rejected tone-map; retrying plain", e)
                        try { decoder?.release() } catch (_: Exception) {}
                        decoder = MediaCodec.createByCodecName(codecName)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "decoder $codecName rejected tone-map; retrying plain", e)
                        try { decoder?.release() } catch (_: Exception) {}
                        decoder = MediaCodec.createByCodecName(codecName)
                    }
                }

                decoder.configure(inputFormat, outputSurface, null, 0)
                decoder.start()
                if (i > 0) Log.w(TAG, "decoder OK with fallback $codecName (attempt ${i + 1}/${candidates.size})")
                return Configured(decoder, codecName, isHdr, isToneMapApplied = false)
            } catch (e: Throwable) {
                Log.w(TAG, "decoder $codecName failed (attempt ${i + 1}/${candidates.size})", e)
                lastError = e
                try { decoder?.release() } catch (_: Exception) {}
            }
        }

        if (isHdr) throw HdrDecoderUnavailableException("All HDR decoder candidates failed for $mime", lastError)
        throw CodecUnavailableException(codecMimeType = mime, isEncoder = false, cause = lastError)
    }

    /** SPEC §11 / signal-reference-notes VideoTrackConverter:670-700. */
    private fun isToneMapEffective(decoder: MediaCodec, codecName: String): Boolean {
        // Software codecs never tone-map.
        val isSoftware = if (Build.VERSION.SDK_INT >= 29) {
            try { decoder.codecInfo.isSoftwareOnly } catch (_: Exception) { false }
        } else {
            val lower = codecName.lowercase(Locale.ROOT)
            lower.startsWith("omx.google.") || lower.startsWith("c2.android.")
        }
        if (isSoftware) {
            Log.w(TAG, "software codec $codecName cannot tone-map")
            return false
        }

        return try {
            val out = decoder.outputFormat
            if (out.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                val t = out.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                if (t == MediaFormat.COLOR_TRANSFER_ST2084 || t == MediaFormat.COLOR_TRANSFER_HLG) {
                    Log.w(TAG, "decoder $codecName accepted tone-map but output transfer still $t")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not verify tone-map for $codecName", e)
            true  // optimistic — if we can't verify, assume it worked
        }
    }

    private fun copyFormat(src: MediaFormat, dst: MediaFormat) {
        // MediaFormat has no public copy ctor on all API levels we support; copy
        // the common keys we care about. (API 29 added `MediaFormat(MediaFormat)`
        // but we still target API 27.) Anything missed here just doesn't make it
        // into the tone-map variant — fine, decoder reads what's needed.
        copyString(src, dst, MediaFormat.KEY_MIME)
        copyInt(src, dst, MediaFormat.KEY_WIDTH)
        copyInt(src, dst, MediaFormat.KEY_HEIGHT)
        copyInt(src, dst, MediaFormat.KEY_COLOR_FORMAT)
        copyInt(src, dst, MediaFormat.KEY_COLOR_STANDARD)
        copyInt(src, dst, MediaFormat.KEY_COLOR_RANGE)
        copyInt(src, dst, MediaFormat.KEY_COLOR_TRANSFER)
        copyInt(src, dst, MediaFormat.KEY_PROFILE)
        copyInt(src, dst, MediaFormat.KEY_LEVEL)
        copyInt(src, dst, MediaFormat.KEY_BIT_RATE)
        copyInt(src, dst, MediaFormat.KEY_FRAME_RATE)
        copyInt(src, dst, MediaFormat.KEY_ROTATION)
        copyLong(src, dst, MediaFormat.KEY_DURATION)
        copyBuffer(src, dst, "csd-0")
        copyBuffer(src, dst, "csd-1")
        copyBuffer(src, dst, "csd-2")
    }

    private fun copyString(s: MediaFormat, d: MediaFormat, k: String) {
        if (s.containsKey(k)) try { d.setString(k, s.getString(k)) } catch (_: Exception) {}
    }
    private fun copyInt(s: MediaFormat, d: MediaFormat, k: String) {
        if (s.containsKey(k)) try { d.setInteger(k, s.getInteger(k)) } catch (_: Exception) {}
    }
    private fun copyLong(s: MediaFormat, d: MediaFormat, k: String) {
        if (s.containsKey(k)) try { d.setLong(k, s.getLong(k)) } catch (_: Exception) {}
    }
    private fun copyBuffer(s: MediaFormat, d: MediaFormat, k: String) {
        if (s.containsKey(k)) try { d.setByteBuffer(k, s.getByteBuffer(k)) } catch (_: Exception) {}
    }
}
