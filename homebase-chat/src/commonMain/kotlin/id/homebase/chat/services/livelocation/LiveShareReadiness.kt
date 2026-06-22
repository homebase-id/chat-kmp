package id.homebase.chat.services.livelocation

/**
 * Whether the current identity can actually start a live-location share — i.e. a share would capture
 * and relay GPS rather than silently send nothing.
 *
 * Implemented in homebase-core (where the location add-on's `locationLabeledDrive` lives) and injected
 * into the chat layer: `ConversationListViewModel` can't reach homebase-core, so this thin seam lets it
 * gate "Share live location" on the location feature being set up + location permission granted, and
 * otherwise prompt the user to set it up first.
 */
fun interface LiveShareReadiness {
    suspend fun isReady(): Boolean
}
