package id.homebase.photobase.gallery

import co.touchlab.kermit.Logger
import id.homebase.core.image.ImageSize
import id.homebase.core.image.selectThumbSize
import id.homebase.core.image.thumbSizesFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Puts the newest photos' grid thumbnails on the device before they're asked for, walking the store
 * so their records come along too. One at a time, so it can't hold up anything a person is waiting on.
 */
class PhotoThumbnailPrefetcher(
    private val store: PhotoGalleryPageSource,
    private val mayDownload: suspend () -> Boolean,
    private val ensureThumbnail: suspend (PhotoGalleryItem, ImageSize) -> Unit,
) {
    suspend fun prefetch() = withContext(Dispatchers.Default) {
        if (!mayDownload()) return@withContext
        var cursor: String? = null
        var seen = 0
        do {
            val page = store.page(cursor, newestFirst = true)
            for (item in page.items.take(RECENT_PHOTOS - seen)) {
                val sizes = thumbSizesFrom(item.payload.thumbnails)
                if (sizes.isEmpty()) continue
                try {
                    ensureThumbnail(item, selectThumbSize(ImageSize.THUMB_SMALL, sizes))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One bad thumbnail mustn't cost the rest; a tile still fetches its own.
                    Logger.w(tag = "PhotoThumbnailPrefetcher", throwable = e) { "prefetch failed for ${item.fileId}" }
                }
            }
            seen += page.items.size
            cursor = page.nextCursor
        } while (cursor != null && seen < RECENT_PHOTOS)
    }

    private companion object {
        const val RECENT_PHOTOS = 500
    }
}
