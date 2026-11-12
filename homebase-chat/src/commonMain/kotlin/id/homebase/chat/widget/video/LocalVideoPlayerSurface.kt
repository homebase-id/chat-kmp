package id.homebase.chat.widget.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Plays the local video at [filePath] inline with the platform's controls, looping until it leaves composition.
 * [onFirstFrameRendered] fires when the first frame is on screen. [hideWhenPaused] hides the video
 * while [isPlaying] is false; only web honours it today, the other targets ignore it.
 */
@Composable
expect fun LocalVideoPlayerSurface(
    filePath: String,
    modifier: Modifier = Modifier,
    onFirstFrameRendered: (() -> Unit)? = null,
)

/**
 * A video surface specialised for the trim screen: clips playback to
 * `[clipStartMs, clipEndMs]`, loops within that range, exposes the current playhead via
 * [onPositionMs] (~30 Hz), and accepts external seek requests via [seekRequestMs] (the
 * player seeks whenever this value changes; pass a fresh value or null).
 *
 * No on-screen playback controls — the trim screen draws its own.
 *
 * [onFirstFrameRendered] fires once per [filePath] when the first frame is on screen. When
 * [hideWhenPaused] is true the surface draws nothing while [isPlaying] is false. A missing or
 * unreadable [filePath] renders nothing and never throws.
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
    onFirstFrameRendered: (() -> Unit)? = null,
    hideWhenPaused: Boolean = false,
)
