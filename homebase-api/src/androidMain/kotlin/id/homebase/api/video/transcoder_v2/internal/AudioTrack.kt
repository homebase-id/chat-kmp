package id.homebase.api.video.transcoder_v2.internal

import android.media.MediaFormat
import android.media.MediaMuxer

/**
 * Audio side of the transcode pipeline. Two implementations:
 *
 * - [AudioPair] — decode → re-encode (AAC-LC at the target bitrate). Used
 *   when the input is non-AAC, or AAC at a bitrate above target.
 * - [AudioRemuxer] — extractor → muxer pass-through. Used when the input
 *   is already AAC and its bitrate is within target. Skips the codec
 *   construction entirely (saves CPU; also dodges a documented HE-AAC
 *   re-encode bug that mangles output PTS — see Signal's
 *   `AudioTrackConverter.java:488-505`).
 *
 * The pump treats both uniformly through this interface. Lazy muxer
 * start works for both: [AudioPair] exposes `encoderOutputFormat` when
 * the encoder emits `INFO_OUTPUT_FORMAT_CHANGED`; [AudioRemuxer] exposes
 * the input format from construction (no encoder to wait for).
 */
internal sealed interface AudioTrack {
    val extractorDone: Boolean

    /**
     * True once no further samples will be written to the muxer.
     * Mirrors `encoderDone` semantically; renamed because [AudioRemuxer]
     * has no encoder.
     */
    val outputDone: Boolean

    /**
     * The `MediaFormat` to pass to `MediaMuxer.addTrack`. Non-null once
     * the output format is known — for [AudioPair] this is after the
     * encoder's first `INFO_OUTPUT_FORMAT_CHANGED`; for [AudioRemuxer]
     * it's the input format, available from construction.
     */
    val encoderOutputFormat: MediaFormat?

    /** Latest muxed PTS in normalized (0-based) space, used by progress + interleave. */
    val muxingPresentationTimeUs: Long

    fun step()
    fun setMuxer(muxer: MediaMuxer, trackIndex: Int)
    fun release(): Throwable?
}
