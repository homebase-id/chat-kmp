package id.homebase.core.avatars

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import id.homebase.api.client.contacts.PublicAvatarRevisions
import id.homebase.api.common.OdinId
import id.homebase.api.common.publicImageUrl

/**
 * The Coil model for an identity's `/pub/image`. The revision rides in the URL because that is the
 * only carrier every cache layer honours: Coil's memory cache keys on it, an unequal model restarts
 * a painter already on screen, and on web — where Coil's default network fetcher runs and the real
 * cache is the browser's, keyed on the URL — nothing else can reach it.
 *
 * Callers that open the same avatar full screen must use this too, or the viewer serves the
 * pre-refresh bytes Coil still holds under the un-busted URL.
 */
@Composable
fun rememberPublicAvatarUrl(odinId: OdinId): String {
    val domain = odinId.domainName
    // A SnapshotStateMap read subscribes the caller to the whole map, so one bump would recompose
    // every avatar on screen; derived state narrows that to this domain's value.
    val revision by remember(domain) { derivedStateOf { PublicAvatarRevisions.revisionOf(domain) } }
    return remember(domain, revision) {
        val url = publicImageUrl(domain)
        if (revision == null) url else "$url?v=$revision"
    }
}
