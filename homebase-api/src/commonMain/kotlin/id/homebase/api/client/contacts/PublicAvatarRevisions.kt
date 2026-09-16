package id.homebase.api.client.contacts

import id.homebase.api.common.OdinId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

/**
 * Cache-bust tokens for peers' `/pub/image`, bumped by [ContactInfoGateway.refresh] and folded by
 * avatar composables into the model string they hand Coil. Coil keys its *memory* cache on that
 * string, so dropping the disk entry alone repaints nothing and never even reaches the fetcher.
 *
 * Deliberately not a Koin binding. Every avatar on screen reads this, including in previews and
 * Compose UI tests that stand up no graph at all, and the state is process-wide, in-memory and
 * dependency-free — a binding would buy nothing and make a leaf composable un-renderable without
 * one. Keyed by [OdinId.domainName]; only identities refreshed this session appear.
 */
object PublicAvatarRevisions {

    private val _revisions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val revisions: StateFlow<Map<String, Long>> = _revisions.asStateFlow()

    internal fun bump(odinId: OdinId) {
        _revisions.update {
            it + (odinId.domainName to Clock.System.now().toEpochMilliseconds())
        }
    }
}
