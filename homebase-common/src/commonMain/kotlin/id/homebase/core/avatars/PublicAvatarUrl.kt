package id.homebase.core.avatars

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.contacts.PublicAvatarRevisions
import id.homebase.api.common.OdinId
import id.homebase.api.common.publicImageUrl
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The `/pub/image` model string to hand Coil for [odinId], carrying whichever cache-bust token is
 * currently live for that identity.
 *
 * Coil keys its memory cache on this string, so an avatar already on screen re-requests only when
 * the string changes — invalidating the disk entry behind it repaints nothing on its own.
 * [cacheBustKey] is the owner's own `sitedata.json` lastModified (no equivalent exists for a peer);
 * [PublicAvatarRevisions] carries the token published by a user-initiated contact refresh. Both are
 * epoch millis, so the newer of the two wins.
 *
 * Callers that open the same image full screen must use this too, or the viewer keeps serving the
 * pre-refresh bytes Coil still holds under the un-busted URL.
 */
@Composable
fun rememberPublicAvatarUrl(odinId: OdinId, cacheBustKey: Long? = null): String {
    val domain = odinId.domainName

    val revision by remember(domain) {
        PublicAvatarRevisions.revisions.map { it[domain] }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = PublicAvatarRevisions.revisions.value[domain])

    val bust = listOfNotNull(cacheBustKey, revision).maxOrNull()
    return remember(domain, bust) {
        publicImageUrl(domain).let { if (bust == null) it else "$it?v=$bust" }
    }
}
