package id.homebase.api.video

data class VideoPayloadProgressPhase(
    val payloadKey: String,
    val phase: VideoProcessingPhase,
    val progress: Float
)