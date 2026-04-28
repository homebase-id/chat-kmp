package id.homebase.core.gallery

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
        PHPhotoLibrary.sharedPhotoLibrary().registerChangeObserver(this)
    }

    override fun photoLibraryDidChange(changeInstance: PHChange) {
        cache.refresh()
    }
}
