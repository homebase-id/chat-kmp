package id.homebase.api.video

enum class VideoProcessingPhase {
    THUMBNAIL,
    COMPRESSING,
    SEGMENTING,

    ENCRYPTING,

    COMPLETE
}