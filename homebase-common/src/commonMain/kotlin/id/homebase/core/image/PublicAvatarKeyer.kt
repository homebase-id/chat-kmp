package id.homebase.core.image

import coil3.Uri
import coil3.key.Keyer
import coil3.request.Options
import id.homebase.api.client.contacts.PublicAvatarRevisions

/**
 * Folds the avatar revision into Coil's memory-cache key for `/pub/image`, so a refreshed identity
 * cannot repaint from bytes cached under the same never-changing URL — whoever requests it, and
 * whether or not that caller knows a refresh happened. Null until the identity is first
 * invalidated, which leaves Coil's own Uri keyer in charge.
 */
class PublicAvatarKeyer(
    // Seam: PublicAvatarRevisions.bump is internal to homebase-api, so a test can't stage one.
    private val revisionOf: (String) -> Long? = PublicAvatarRevisions::revisionOf,
) : Keyer<Uri> {

    override fun key(data: Uri, options: Options): String? {
        val odinId = PublicImageFetcher.resolveOdinId(data) ?: return null
        val revision = revisionOf(odinId.domainName) ?: return null
        return "$data|v=$revision"
    }
}
