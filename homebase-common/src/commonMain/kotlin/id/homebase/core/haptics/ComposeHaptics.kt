package id.homebase.core.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.settings.UserPreferences
import org.koin.compose.koinInject

/** Maps a semantic [HapticEvent] to Compose's [HapticFeedbackType]. */
internal fun HapticEvent.toComposeType(): HapticFeedbackType = when (this) {
    HapticEvent.Selection -> HapticFeedbackType.TextHandleMove
    HapticEvent.LongPress -> HapticFeedbackType.LongPress
    HapticEvent.Confirm -> HapticFeedbackType.Confirm
}

/** [Haptics] backed by Compose's [HapticFeedback] — the only Compose-aware impl. */
class ComposeHaptics(
    private val hapticFeedback: HapticFeedback,
) : Haptics {
    override fun perform(event: HapticEvent) {
        hapticFeedback.performHapticFeedback(event.toComposeType())
    }
}

/**
 * The app-wide way to obtain [Haptics] inside a composable. Reads
 * `LocalHapticFeedback` at composition (so it works from gesture lambdas, the
 * same way the raw API did) and gates on the persisted `hapticsEnabled`
 * preference.
 */
@Composable
fun rememberHaptics(): Haptics {
    val hapticFeedback = LocalHapticFeedback.current
    val userPreferences: UserPreferences = koinInject()
    // No `by` delegate — keep the State object so the enabled lambda reads
    // `.value` live on each perform (matters for the per-degree RotationDial path).
    val prefState = userPreferences.preferenceState.collectAsStateWithLifecycle()
    return remember(hapticFeedback) {
        GatedHaptics(
            delegate = ComposeHaptics(hapticFeedback),
            enabled = { prefState.value.hapticsEnabled },
        )
    }
}
