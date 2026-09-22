package id.homebase.core.gallery

import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Photos.PHChange
import platform.Photos.PHPhotoLibrary
import platform.Photos.PHPhotoLibraryChangeObserverProtocol
import platform.darwin.NSObject

/**
 * Bridges PHPhotoLibrary change notifications into [GalleryCache.refresh] so reopening
 * the picker after the user adds/removes a photo shows the latest gallery state.
 *
 * Held by the Koin singleton scope so the observer outlives any composables.
 */
@OptIn(ExperimentalForeignApi::class)
class IOSGalleryLibraryObserver(private val cache: GalleryCache) :
    NSObject(), PHPhotoLibraryChangeObserverProtocol {

    init {
        pendingObserver = this
    }

    override fun photoLibraryDidChange(changeInstance: PHChange) {
        cache.refresh()
    }
}

@Volatile
private var pendingObserver: IOSGalleryLibraryObserver? = null

@Volatile
private var observerRegistered = false

// Any Photos call on the main thread at startup raises the permission dialog — even the
// read-only status check. Registration is driven from the background fetch instead, which
// only reaches here once access is already granted.
@OptIn(ExperimentalForeignApi::class)
internal fun registerGalleryObserverIfAuthorized() {
    if (observerRegistered) return
    val observer = pendingObserver ?: return
    if (!photoLibraryReadAuthorized()) return
    observerRegistered = true
    PHPhotoLibrary.sharedPhotoLibrary().registerChangeObserver(observer)
}
