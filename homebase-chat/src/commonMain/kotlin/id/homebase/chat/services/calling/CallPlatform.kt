package id.homebase.chat.services.calling

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The real engine for this target, or null where calling is not supported (the UI then hides every entry point). */
expect fun platformCallEngineFactory(): CallEngineFactory?

/** Renders a [VideoHandle] produced by this target's engine. */
@Composable
expect fun CallVideoView(handle: VideoHandle?, modifier: Modifier = Modifier)

/** Audible/visible ring for an incoming call where the OS does not already provide one (desktop). */
@Composable
expect fun CallRingEffect(ringing: Boolean, callerName: String)

/** Output routes the OS offers right now; fewer than two means the route picker is hidden. */
expect fun platformAudioRoutes(): List<AudioRoute>
