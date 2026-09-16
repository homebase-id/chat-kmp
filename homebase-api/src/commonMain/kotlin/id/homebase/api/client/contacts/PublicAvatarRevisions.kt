package id.homebase.api.client.contacts

import androidx.compose.runtime.mutableStateMapOf
import id.homebase.api.common.OdinId
import kotlin.time.Clock

/**
 * Cache-bust tokens for peers' `/pub/image`: Coil keys its memory cache on the model string, and a
 * peer's avatar URL never changes on its own.
 *
 * Not a Koin binding — `PublicAvatar` is a leaf that 19 homebase-chat Compose tests render in an
 * isolated graph holding only `UserPreferences` + an `ImageLoader`, and a second required binding
 * fails all of them. [ContactInfoGateway.clearCaches] prunes it, so it outlives no identity.
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
