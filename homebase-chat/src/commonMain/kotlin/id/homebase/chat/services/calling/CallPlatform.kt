package id.homebase.chat.services.calling

import androidx.compose.runtime.Composable
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

/** Output routes the OS offers, again whenever a headset comes or goes; fewer than two hides the route picker. */
expect fun audioRouteChanges(): Flow<List<AudioRoute>>
