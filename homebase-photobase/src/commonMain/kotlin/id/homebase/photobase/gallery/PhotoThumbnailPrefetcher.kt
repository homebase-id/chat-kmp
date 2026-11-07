package id.homebase.photobase.gallery

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.core.image.thumbSizeToRequest
import kotlinx.coroutines.CancellationException

/**
 * Puts the grid's thumbnails for the newest photos on the device before they're asked for. Reads
 * the same cache entries a tile does, so a photo already held costs one disk lookup.
 */
class PhotoThumbnailPrefetcher(
    private val mayDownload: suspend () -> Boolean,
    private val fetchThumbnail: suspend (PhotoGalleryItem, ImageSize) -> Unit,
) {
    suspend fun prefetch(newestFirst: List<PhotoGalleryItem>) {
        if (!mayDownload()) return
        for (item in newestFirst.take(RECENT_PHOTOS)) {
            try {
                val iv = item.payload.iv?.bytes ?: continue
                val data = HomebaseImageData.from(
                    driveId = item.driveId,
                    fileId = item.fileId,
                    descriptor = item.payload,
                    keyHeader = KeyHeader(iv = iv, aesKey = item.keyHeader.aesKey),
                    thumbSizeLimit = ImageSize.THUMB_SMALL,
                )
                if (data.availableThumbSizes.isEmpty()) continue
                fetchThumbnail(item, thumbSizeToRequest(ImageSize.THUMB_SMALL, data))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One bad thumbnail mustn't cost the rest; a tile still fetches its own.
                Logger.w(tag = "PhotoThumbnailPrefetcher", throwable = e) { "prefetch failed for ${item.fileId}" }
            }
        }
    }

    private companion object {
        const val RECENT_PHOTOS = 500
    }
}
