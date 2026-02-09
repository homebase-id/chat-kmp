package id.homebase.chat.widget

data class VideoDebugState(
    val status: String = "INIT",
    val buffered: String = "",
    val error: String? = null
)