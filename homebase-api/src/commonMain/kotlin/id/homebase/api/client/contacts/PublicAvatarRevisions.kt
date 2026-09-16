package id.homebase.api.client.contacts

import androidx.compose.runtime.mutableStateMapOf
import id.homebase.api.common.OdinId
import kotlin.time.Clock

/**
 * The single "this identity's avatar changed" signal, published by
 * [id.homebase.api.client.profile.PublicProfileProviderCached.invalidateImage] — the owner's own
 * republish and a peer resync both land there.
 */
// Not a Koin binding: homebase-chat's Compose tests build a graph with no room for a second
// required binding.
object PublicAvatarRevisions {

    private val revisions = mutableStateMapOf<String, Long>()

    // Snapshot state: an avatar composable reading this recomposes when bump() lands.
    fun revisionOf(domain: String): Long? = revisions[domain]

    // Monotonic, not just "now": a device clock stepping backwards would otherwise hand back a
    // token Coil already holds bytes under, silently disarming the refresh. Never reset — a token
    // that goes backwards is the one failure this cannot recover from.
    internal fun bump(odinId: OdinId) {
        val domain = odinId.domainName
        val previous = revisions[domain] ?: 0L
        revisions[domain] = maxOf(Clock.System.now().toEpochMilliseconds(), previous + 1)
    }
}
