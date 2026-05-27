package id.homebase.core.media.subsample

import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.HomebaseImageLoader

sealed interface SubSamplingImageSource {
    suspend fun loadBytes(): ByteArray?

    class Remote(
        val imageData: HomebaseImageData,
        val imageLoader: HomebaseImageLoader,
    ) : SubSamplingImageSource {
        override suspend fun loadBytes(): ByteArray? {
            return imageLoader.loadFullPayload(imageData)?.bytes
        }
    }

    class LocalFile(
        val filePath: String,
        val readFileBytes: suspend (String) -> ByteArray,
    ) : SubSamplingImageSource {
        override suspend fun loadBytes(): ByteArray? {
            return try {
                readFileBytes(filePath)
            } catch (_: Exception) {
                null
            }
        }
    }
}
