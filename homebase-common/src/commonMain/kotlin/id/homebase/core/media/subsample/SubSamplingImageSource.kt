package id.homebase.core.media.subsample

import id.homebase.core.image.HomebaseImageData

sealed interface SubSamplingImageSource {

    class Remote(
        val imageData: HomebaseImageData,
    ) : SubSamplingImageSource

    class LocalFile(
        val filePath: String,
    ) : SubSamplingImageSource

    class Url(
        val url: String,
    ) : SubSamplingImageSource
}

/**
 * Shared-element key for a source, or null when the source has no stable identity to pair on.
 * The [SubSamplingImageSource.Remote] form must stay byte-identical to the key
 * `HomebaseImage` registers, or a drive-backed image and its full-screen viewer won't match.
 */
fun SubSamplingImageSource.sharedElementKey(): String? = when (this) {
    is SubSamplingImageSource.Remote -> "image-${imageData.fileId}-${imageData.payloadKey}"
    is SubSamplingImageSource.Url -> imageUrlSharedElementKey(url)
    is SubSamplingImageSource.LocalFile -> null
}

fun imageUrlSharedElementKey(url: String): String = "image-url-$url"
