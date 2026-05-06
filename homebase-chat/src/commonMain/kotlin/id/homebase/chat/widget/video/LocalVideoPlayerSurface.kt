package id.homebase.chat.widget.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun LocalVideoPlayerSurface(
    filePath: String,
    modifier: Modifier = Modifier,
    onFirstFrameRendered: () -> Unit = {},
)

/**
 * A video surface specialised for the trim screen: clips playback to
 * `[clipStartMs, clipEndMs]`, loops within that range, exposes the current playhead via
 * [onPositionMs] (~30 Hz), and accepts external seek requests via [seekRequestMs] (the
 * player seeks whenever this value changes; pass a fresh value or null).
 *
 * No on-screen playback controls — the trim screen draws its own.
 */
@Composable
expect fun TrimmableVideoPlayerSurface(
    filePath: String,
    clipStartMs: Long,
    clipEndMs: Long,
    isPlaying: Boolean,
    seekRequestMs: Long?,
    onPositionMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onFirstFrameRendered: () -> Unit = {},
)
