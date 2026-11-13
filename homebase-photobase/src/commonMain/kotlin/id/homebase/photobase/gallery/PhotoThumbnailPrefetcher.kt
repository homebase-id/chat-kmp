package id.homebase.photobase.gallery

import co.touchlab.kermit.Logger
import id.homebase.core.image.ImageSize
import id.homebase.core.image.GRID_SHORTFALL_PERCENT
import id.homebase.core.image.selectThumbSize
import id.homebase.core.image.thumbSizesFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    private var finished = false
    private val paused = MutableStateFlow(false)

    /** While someone scrolls the grid, so the tiles they stop on don't queue behind the prefetch. */
    fun pause(paused: Boolean) {
        this.paused.value = paused
    }

    /** Once a process: after that, a new photo's tile fetches its own, and re-walking 500 records buys nothing. */
    suspend fun prefetch() = withContext(Dispatchers.Default) {
        if (finished) return@withContext
        var cursor: String? = null
        var seen = 0
        do {
            if (!mayDownload()) return@withContext
            val page = store.page(cursor, newestFirst = true)
            for (item in page.items.take(RECENT_PHOTOS - seen)) {
                val sizes = thumbSizesFrom(item.payload.thumbnails)
                if (sizes.isEmpty()) continue
                paused.first { !it }
                try {
                    ensureThumbnail(item, selectThumbSize(PHONE_GRID_TILE, sizes, crop = true, GRID_SHORTFALL_PERCENT))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One bad thumbnail mustn't cost the rest, but losing the network or moving to
                    // mobile data ends the walk.
                    Logger.w(tag = "PhotoThumbnailPrefetcher", throwable = e) { "prefetch failed for ${item.fileId}" }
                    if (!mayDownload()) return@withContext
                }
            }
            seen += page.items.size
            cursor = page.nextCursor
        } while (cursor != null && seen < RECENT_PHOTOS)
        finished = true
    }

    fun reset() {
        finished = false
    }

    private companion object {
        const val RECENT_PHOTOS = 500
    }
}

// Phone grid tiles are 270–330 px, so this picks what they will; a tablet's bigger tiles fetch their own.
private val PHONE_GRID_TILE = ImageSize(320, 320)
