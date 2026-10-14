package id.homebase.chat.services.calling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow

/** The real engine for this target, or null where calling is not supported (the UI then hides every entry point). */
expect fun platformCallEngineFactory(): CallEngineFactory?

/**
 * Renders a [VideoHandle] produced by this target's engine, rounded to [cornerRadius].
 * A native video surface does not obey Compose's clip, so each target rounds its own view.
 */
@Composable
expect fun CallVideoView(handle: VideoHandle?, modifier: Modifier = Modifier, cornerRadius: Dp = 0.dp)

/** One native renderer per track, re-parented as the call moves between full screen, tile and PiP; without it in [LocalCallVideoRenderers] each view owns its own. Holds nothing on desktop and web. */
expect class CallVideoRenderers() {
    /** Releases every renderer whose track is not among [handles]. */
    fun keepOnly(handles: List<VideoHandle>)
}

val LocalCallVideoRenderers = staticCompositionLocalOf<CallVideoRenderers?> { null }

/** Output routes the OS offers, again whenever a headset comes or goes; fewer than two hides the route picker. */
expect fun audioRouteChanges(): Flow<List<AudioRoute>>

/** True while a screen reader (TalkBack, VoiceOver) drives the UI, which then must not hide controls on a timer. False where it cannot be told. */
@Composable
expect fun rememberScreenReaderOn(): Boolean
