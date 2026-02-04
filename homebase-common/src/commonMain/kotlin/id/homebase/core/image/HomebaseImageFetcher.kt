package id.homebase.core.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.github.vinceglb.filekit.extension
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.readBytes
import okio.Buffer

/**
 * Custom Coil3 Fetcher for loading encrypted Homebase images.
 *
 * This fetcher handles [HomebaseImageData] requests by:
 * 1. Checking cache for larger existing images
 * 2. Fetching and decrypting from server
 * 3. Returning bytes as a Coil source
 */
class HomebaseImageFetcher(
        private val data: HomebaseImageData,
        private val options: Options,
        private val homebaseImageLoader: HomebaseImageLoader
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        // Handle pending files
        if (data.isPending) {
            val result = loadPendingFileInternal(data)
            return result?.toFetchResult()
        }

        // Determine target size from Coil options
        val width = (options.size.width as? coil3.size.Dimension.Pixels)?.px ?: 1600
        val height = (options.size.height as? coil3.size.Dimension.Pixels)?.px ?: 1600
        val targetSize = ImageSize(width, height)

        // Load image
        val result =
                if (data.loadFullPayload) {
                    homebaseImageLoader.loadFullPayload(data)
                } else {
                    homebaseImageLoader.loadThumbnail(data, targetSize)
                }

        return result?.toFetchResult()
    }

    private fun CachedImage.toFetchResult(): FetchResult {
        val buffer = Buffer().write(bytes)
        val imageSource = ImageSource(buffer, options.fileSystem)

        return SourceFetchResult(
                source = imageSource,
                mimeType = contentType,
                dataSource = DataSource.DISK
        )
    }

    /** Load pending/local file from filesystem */
    private suspend fun loadPendingFileInternal(data: HomebaseImageData): CachedImage? {
        val file = data.pendingFile ?: return null
        return try {
            val bytes = file.readBytes()
            val contentType = file.mimeType()?.toString() ?: file.extension
            CachedImage(bytes, contentType, null)
        } catch (e: Exception) {
            null
        }
    }

    /** Factory for creating HomebaseImageFetcher instances */
    class Factory(private val homebaseImageLoader: HomebaseImageLoader) :
            Fetcher.Factory<HomebaseImageData> {

        override fun create(
                data: HomebaseImageData,
                options: Options,
                imageLoader: ImageLoader
        ): Fetcher = HomebaseImageFetcher(data, options, this.homebaseImageLoader)
    }
}
