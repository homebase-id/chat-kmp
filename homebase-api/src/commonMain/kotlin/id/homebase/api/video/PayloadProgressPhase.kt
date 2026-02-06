package id.homebase.api.video

data class PayloadProgressPhase(
    val payloadKey: String,
    val phase: String, // "thumbnail" | "segmenting" | later "compressing"
    val progress: Float
)