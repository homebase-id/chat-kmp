package id.homebase.core.diagnostics

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Forced-recomposition lever, kept from the v1 iOS blank-text recovery experiment.
 *
 * `App()` reads [epoch] inside a `key(...)` wrapping the UI tree; bumping it disposes and recomposes
 * the whole subtree, so every `Text` re-shapes and re-issues its glyph draws from scratch.
 *
 * NOTE: as a blank-text recovery this was FIELD-FALSIFIED (3×, 2026-06): recomposed text with fresh
 * CPU strikes still samples the dead per-context GPU glyph atlas, which no purge or recompose can
 * reach. The shake recovery v2 recreates the Compose view controller from Swift instead (fresh
 * GrDirectContext). This lever is retained as a generic full-recompose hook; currently un-bumped.
 *
 * Bump only from the main thread (it writes Compose snapshot state). No-op on platforms that never
 * bump it.
 */
object TextRecovery {
    val epoch: MutableState<Int> = mutableStateOf(0)

    fun forceRecompose() {
        epoch.value = epoch.value + 1
    }
}
