package id.homebase.core.media.subsample

import id.homebase.api.common.OdinId
import id.homebase.core.image.HomebaseImageData

sealed interface SubSamplingImageSource {

    class Remote(
        val imageData: HomebaseImageData,
    ) : SubSamplingImageSource

    class LocalFile(
        val filePath: String,
    ) : SubSamplingImageSource

    /** An identity's published `/pub/image`, keyed on the identity so no caller handles its URL. */
    class Avatar(
        val odinId: OdinId,
    ) : SubSamplingImageSource
}

/**
 * Shared-element key for a source, or null when the source has no stable identity to pair on.
 * The [SubSamplingImageSource.Remote] form must stay byte-identical to the key
 * `HomebaseImage` registers, or a drive-backed image and its full-screen viewer won't match.
 */
fun SubSamplingImageSource.sharedElementKey(): String? = when (this) {
    is SubSamplingImageSource.Remote -> "image-${imageData.fileId}-${imageData.payloadKey}"
    is SubSamplingImageSource.Avatar -> avatarSharedElementKey(odinId)
    is SubSamplingImageSource.LocalFile -> null
}

fun avatarSharedElementKey(odinId: OdinId): String = "avatar-${odinId.domainName}"
