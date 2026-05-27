package id.homebase.core.media.subsample

import id.homebase.core.image.HomebaseImageData

sealed interface SubSamplingImageSource {

    class Remote(
        val imageData: HomebaseImageData,
    ) : SubSamplingImageSource

    class LocalFile(
        val filePath: String,
    ) : SubSamplingImageSource
}
