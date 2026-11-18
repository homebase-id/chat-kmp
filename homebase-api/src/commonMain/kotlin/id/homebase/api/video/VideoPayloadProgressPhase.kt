package id.homebase.api.video

import kotlin.uuid.Uuid

data class VideoPayloadProgressPhase(
    val payloadKey: String,
    val phase: VideoProcessingPhase,
    val progress: Float
)

/** How far the whole video is, 0..1, from how far its current phase is. */
fun videoOverallFraction(phase: VideoProcessingPhase, progress: Float): Float =
    when (phase) {
        VideoProcessingPhase.THUMBNAIL -> 0f
        VideoProcessingPhase.COMPRESSING -> 0.9f * progress
        VideoProcessingPhase.SEGMENTING -> 0.9f + 0.1f * progress
        VideoProcessingPhase.ENCRYPTING -> 0.9f
        VideoProcessingPhase.COMPLETE -> 1f
    }
