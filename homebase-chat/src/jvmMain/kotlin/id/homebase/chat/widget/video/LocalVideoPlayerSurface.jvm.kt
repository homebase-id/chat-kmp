package id.homebase.chat.widget.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun LocalVideoPlayerSurface(
    filePath: String,
    modifier: Modifier,
    onFirstFrameRendered: (() -> Unit)?,
) {
    DesktopVideoPlayer(videoPath = filePath, modifier = modifier, onFirstFrameRendered = onFirstFrameRendered ?: {})
}

@Composable
actual fun TrimmableVideoPlayerSurface(
    filePath: String,
    clipStartMs: Long,
    clipEndMs: Long,
    isPlaying: Boolean,
    seekRequestMs: Long?,
    onPositionMs: (Long) -> Unit,
    modifier: Modifier,
    onFirstFrameRendered: (() -> Unit)?,
) {
    DesktopVideoPlayer(
        videoPath = filePath,
        modifier = modifier,
        onFirstFrameRendered = onFirstFrameRendered ?: {},
        showControls = false,
        clipStartMs = clipStartMs,
        clipEndMs = clipEndMs,
        externalIsPlaying = isPlaying,
        seekRequestMs = seekRequestMs,
        onPositionMs = onPositionMs,
    )
}
