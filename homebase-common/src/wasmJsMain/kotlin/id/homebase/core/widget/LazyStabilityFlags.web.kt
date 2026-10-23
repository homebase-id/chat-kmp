package id.homebase.core.widget

import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * Kotlin/Wasm sets `RichTextState`'s Compose stability flag in its file's lazy initialiser, which the
 * chat composer otherwise first reaches on the first edit. A flag that changes after a composable
 * has composed shifts its slot reads, so call this before the first composition.
 */
fun initLazyStabilityFlags() {
    // removeLink always records history, and that is what runs RichTextState.kt's initialiser.
    RichTextState().removeLink()
}
