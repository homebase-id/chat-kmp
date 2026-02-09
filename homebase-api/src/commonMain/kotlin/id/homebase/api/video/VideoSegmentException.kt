package id.homebase.api.video

class VideoSegmentException(
    message: String,
    val command: List<String>,
    val exitCode: Int,
    val ffmpegOutput: String
) : RuntimeException(
    """
    $message
    Exit code: $exitCode
    Command: ${command.joinToString(" ")}
    FFmpeg output:
    $ffmpegOutput
    """.trimIndent()
)