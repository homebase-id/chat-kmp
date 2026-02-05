package id.homebase.core.gallery

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSSortDescriptor
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHFetchOptions

class IOSGalleryManager: PlatformGalleryManager {
    override suspend fun fetchGalleryImages(limit: Int): List<PlatformFile> = withContext(Dispatchers.Main) {
        val images = mutableListOf<GalleryImage>()

        val fetchOptions = PHFetchOptions().apply {
            sortDescriptors = listOf(
                NSSortDescriptor("creationDate", false)
            )
            fetchLimit = limit.toULong()
        }

        val fetchResult = PHAsset.fetchAssetsWithMediaType(
            PHAssetMediaTypeImage,
            fetchOptions
        )

        for (i in 0 until fetchResult.count.toInt()) {
            val asset = fetchResult.objectAtIndex(i.toULong()) as PHAsset
            images.add(GalleryImage(
                id = asset.localIdentifier,
                uri = asset.localIdentifier,
                dateAdded = asset.creationDate?.timeIntervalSince1970?.toLong() ?: 0L,
                mimeType = "image/*"
            ))
        }
        images.map { PlatformFile(it.uri) }
    }
}