package id.homebase.api.video.transcoder_v2.internal

import android.media.MediaMuxer
import android.util.Log
import id.homebase.api.video.transcoder_v2.TranscodeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive

/**
 * The main while-loop. Owns the muxer and the two pairs; interleaves
 * `step()` calls, performs lazy muxer start, watchdogs stuck frames,
 * and emits progress updates. SPEC §5 / §9.3 / §9.7 / §9.8.
 *
 * Resource teardown is the caller's job (factory and pump are
 * independent so retry-with-exclusion can reuse the muxer file path
 * cleanly).
 */
internal class TranscodePump(
    private val videoPair: VideoPair,
    private val audioPair: AudioTrack?,
    private val muxer: MediaMuxer,
    private val rotationDeg: Int,
    private val totalDurationUs: Long,
    private val onProgress: ((Float) -> Unit)?,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "TranscodePump"
        private const val STUCK_FRAME_THRESHOLD = 100
    }

    private var muxing = false
    private var lastEmittedPercent = -1
    private var stuckIterations = 0
    private var lastState: PumpState? = null

    fun run() {
        // Rotation hint must be set BEFORE muxer.start(). SPEC §10.
        try { muxer.setOrientationHint(((rotationDeg % 360) + 360) % 360) } catch (_: Exception) {}

        while (!isComplete()) {
            scope.ensureActive()
            stepInterleaved()
            maybeStartMuxer()
            maybeEmitProgress()
            checkStuckFrames()
        }
    }

    private fun isComplete(): Boolean =
        videoPair.encoderDone && (audioPair == null || audioPair.outputDone)

    /** SPEC §9.7. Whichever side has the LOWER muxing PTS gets stepped. */
    private fun stepInterleaved() {
        val a = audioPair
        if (a == null) {
            videoPair.step()
            return
        }
        if (a.extractorDone || videoPair.muxingPresentationTimeUs <= a.muxingPresentationTimeUs) {
            videoPair.step()
        }
        if (videoPair.extractorDone || videoPair.muxingPresentationTimeUs >= a.muxingPresentationTimeUs) {
            a.step()
        }
    }

    /** SPEC §9.8. Don't start the muxer until BOTH encoders have emitted INFO_OUTPUT_FORMAT_CHANGED. */
    private fun maybeStartMuxer() {
        if (muxing) return
        val vFmt = videoPair.encoderOutputFormat ?: return
        val aFmt = audioPair?.encoderOutputFormat
        if (audioPair != null && aFmt == null) return

        val vTrack = muxer.addTrack(vFmt)
        videoPair.setMuxer(muxer, vTrack)
        if (audioPair != null && aFmt != null) {
            val aTrack = muxer.addTrack(aFmt)
            audioPair.setMuxer(muxer, aTrack)
        }
        muxer.start()
        muxing = true
        Log.d(TAG, "muxer started (rotation=$rotationDeg)")
    }

    /** SPEC §2 progress contract + signal-reference-notes MediaConverter:358-371. */
    private fun maybeEmitProgress() {
        val cb = onProgress ?: return
        if (totalDurationUs <= 0) return
        // PTS is normalized to 0-based at encoder-queue time (VideoPair +
        // AudioPair subtract trimStartUs there), so no further trim subtract here.
        val cur = maxOf(videoPair.muxingPresentationTimeUs, audioPair?.muxingPresentationTimeUs ?: 0)
        val pct = (100.0 * cur.coerceAtLeast(0L) / totalDurationUs).toInt().coerceIn(0, 100)
        if (pct != lastEmittedPercent) {
            lastEmittedPercent = pct
            cb(pct / 100f)
        }
    }

    private data class PumpState(
        val vExtracted: Int,
        val vDecoded: Int,
        val vEncoded: Int,
        val vMuxPts: Long,
        val aMuxPts: Long,
        val muxing: Boolean,
    )

    /** SPEC §9.3 stuck-frame watchdog. ~1s of no progress → bail. */
    private fun checkStuckFrames() {
        if (!muxing) return
        val state = PumpState(
            vExtracted = videoPair.extractedFrames,
            vDecoded = videoPair.decodedFrames,
            vEncoded = videoPair.encodedFrames,
            vMuxPts = videoPair.muxingPresentationTimeUs,
            aMuxPts = audioPair?.muxingPresentationTimeUs ?: 0L,
            muxing = true,
        )
        if (state == lastState) {
            stuckIterations++
            if (stuckIterations >= STUCK_FRAME_THRESHOLD) {
                throw TranscodeException(
                    "Codec stalled — no frames after ~1s of pumping (decoder=${videoPair.decoderName}, " +
                        "vExtracted=${state.vExtracted}, vDecoded=${state.vDecoded}, vEncoded=${state.vEncoded})",
                    attemptedDecoder = videoPair.decoderName,
                )
            }
        } else {
            lastState = state
            stuckIterations = 0
        }
    }
}
