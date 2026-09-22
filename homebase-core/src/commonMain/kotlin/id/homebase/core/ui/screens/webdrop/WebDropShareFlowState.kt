package id.homebase.core.ui.screens.webdrop

import id.homebase.core.ui.screens.webdrop.model.PickedDropFile

/**
 * In-memory hand-off from the platform share sheet into the WebDrop composer.
 * The share flow materializes the shared files to real paths, seeds this, and
 * deep-links into `Route.WebDrop`; [WebDropViewModel] consumes the draft on
 * init and opens the compose sheet with the files already picked.
 *
 * Consume-once by design — unlike the moments draft there is no second screen
 * that needs to rehydrate from it, and a stale draft must never resurface on a
 * later, unrelated visit to the WebDrop screen. A Koin singleton, so it is
 * [clear]ed on identity switch alongside the other WebDrop singletons.
 */
class WebDropShareFlowState {

    private var draft: List<PickedDropFile>? = null

    fun setDraft(files: List<PickedDropFile>) {
        draft = files.takeIf { it.isNotEmpty() }
    }

    /** Returns the seeded files and forgets them. */
    fun consume(): List<PickedDropFile>? = draft.also { draft = null }

    fun clear() {
        draft = null
    }
}
