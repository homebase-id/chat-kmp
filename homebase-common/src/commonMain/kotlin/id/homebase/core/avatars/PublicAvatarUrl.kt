package id.homebase.core.avatars

import androidx.compose.runtime.Composable
import id.homebase.api.client.contacts.PublicAvatarRevisions
import id.homebase.api.common.OdinId
import id.homebase.api.common.publicImageUrl

/**
 * Callers that open this same image full screen must use this too, or the viewer keeps serving the
 * pre-refresh bytes Coil still holds under the un-busted URL.
 */
@Composable
fun rememberPublicAvatarUrl(odinId: OdinId, cacheBustKey: Long? = null): String {
    val domain = odinId.domainName
    val revision = PublicAvatarRevisions.revisionOf(domain)
    val bust = when {
        cacheBustKey == null -> revision
        revision == null -> cacheBustKey
        else -> maxOf(cacheBustKey, revision)
    }
    val url = publicImageUrl(domain)
    return if (bust == null) url else "$url?v=$bust"
}
