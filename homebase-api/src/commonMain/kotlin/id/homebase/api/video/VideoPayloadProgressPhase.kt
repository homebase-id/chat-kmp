package id.homebase.api.video

import kotlin.uuid.Uuid

data class VideoPayloadProgressPhase(
    val payloadKey: String,
    val phase: VideoProcessingPhase,
    val progress: Float
)