package id.homebase.api.client.contacts

import androidx.compose.runtime.mutableStateMapOf
import id.homebase.api.common.OdinId
import kotlin.time.Clock

/**
 * The single "this identity's avatar changed" signal, published by
 * [id.homebase.api.client.profile.PublicProfileProviderCached.invalidateImage] — the owner's own
 * republish and a peer resync both land there.
 *
 * A process-wide object rather than a Koin binding: 19 homebase-chat Compose tests render an avatar
 * in an isolated graph holding only `UserPreferences` + an `ImageLoader`, and a second required
 * binding fails all of them.
 */
object PublicAvatarRevisions {

    private val revisions = mutableStateMapOf<String, Long>()

    // Snapshot state: an avatar composable reading this recomposes when bump() lands.
    fun revisionOf(domain: String): Long? = revisions[domain]

    // Monotonic, not just "now": a device clock stepping backwards would otherwise hand back a
    // token Coil already holds bytes under, silently disarming the refresh.
    internal fun bump(odinId: OdinId) {
        val domain = odinId.domainName
        val previous = revisions[domain] ?: 0L
        revisions[domain] = maxOf(Clock.System.now().toEpochMilliseconds(), previous + 1)
    }

    internal fun clear() = revisions.clear()
}
