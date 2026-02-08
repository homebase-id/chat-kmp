package id.homebase.chat.widget.video

sealed class VideoPlaybackPreparationResult {
    data class Success(val url: String, val contentId: String) : VideoPlaybackPreparationResult()
    data class Error(val message: String) : VideoPlaybackPreparationResult()
}