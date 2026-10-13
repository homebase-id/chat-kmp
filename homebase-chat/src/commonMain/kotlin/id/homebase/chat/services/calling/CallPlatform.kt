package id.homebase.chat.services.calling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The real engine for this target, or null where calling is not supported (the UI then hides every entry point). */
expect fun platformCallEngineFactory(): CallEngineFactory?

/**
 * Renders a [VideoHandle] produced by this target's engine, rounded to [cornerRadius].
 * A native video surface does not obey Compose's clip, so each target rounds its own view.
 */
@Composable
expect fun CallVideoView(handle: VideoHandle?, modifier: Modifier = Modifier, cornerRadius: Dp = 0.dp)

/**
 * Owns one native renderer per video track for as long as the call UI shows it, so moving a video between the full
 * call screen, the floating in-app tile and the PiP layout re-parents the same renderer instead of tearing one down
 * and starting another. [CallVideoView] uses it when provided through [LocalCallVideoRenderers]; without one, each
 * view owns its renderer. Desktop and web keep per-view renderers, so there it holds nothing.
 */
expect class CallVideoRenderers() {
    /** Releases every renderer whose track is not among [handles]. */
    fun keepOnly(handles: List<VideoHandle>)

    fun releaseAll()
}

val LocalCallVideoRenderers = staticCompositionLocalOf<CallVideoRenderers?> { null }

/** Output routes the OS offers right now; fewer than two means the route picker is hidden. */
expect fun platformAudioRoutes(): List<AudioRoute>

/** True while a screen reader (TalkBack, VoiceOver) drives the UI, which then must not hide controls on a timer. False where it cannot be told. */
@Composable
expect fun rememberScreenReaderOn(): Boolean
