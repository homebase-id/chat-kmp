package id.homebase.core.diagnostics

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Forced-recomposition lever for the iOS blank-text recovery experiment.
 *
 * `App()` reads [epoch] inside a `key(...)` wrapping the UI tree; bumping it disposes and recomposes
 * the whole subtree, so every `Text` re-shapes and re-issues its glyph draws from scratch. Combined
 * with a Skia cache purge, that is the recovery we test when the user shakes the device during a
 * blank screen (see the iOS `onBlankTextShake`). The `navController` is remembered ABOVE the `key`,
 * so navigation state survives the recomposition.
 *
 * Bump only from the main thread (it writes Compose snapshot state) — the iOS shake handler runs on
 * main. No-op on platforms that never bump it (the bug is iOS-only).
 */
object TextRecovery {
    val epoch: MutableState<Int> = mutableStateOf(0)

    fun forceRecompose() {
        epoch.value = epoch.value + 1
    }
}
