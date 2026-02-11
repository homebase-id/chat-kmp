package id.homebase.core.gallery

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSSortDescriptor
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAsset
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionTypeAlbum
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetResource
import platform.Photos.PHFetchOptions

class IOSGalleryManager: PlatformGalleryManager {
    override suspend fun fetchGalleryImages(limit: Int): List<GalleryImage> = withContext(Dispatchers.Main) {
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

            // Get the file name from asset resources
            val resources = PHAssetResource.assetResourcesForAsset(asset)
            val fileName = if (resources.isNotEmpty()) {
                (resources.first() as? PHAssetResource)?.originalFilename ?: "unknown.jpg"
            } else {
                "unknown.jpg"
            }

            // Get the album/collection name
            val collections = PHAssetCollection.fetchAssetCollectionsContainingAsset(
                asset,
                PHAssetCollectionTypeAlbum,
                null
            )
            val galleryName = if (collections.count > 0u) {
                (collections.objectAtIndex(0u) as? PHAssetCollection)?.localizedTitle ?: ""
            } else {
                ""
            }

            images.add(GalleryImage(
                id = asset.localIdentifier,
                file = PlatformFile(asset.localIdentifier),
                thumbnailUri = "ph://${asset.localIdentifier}",
                dateAdded = asset.creationDate?.timeIntervalSince1970?.toLong() ?: 0L,
                mimeType = "image/*",
                fileName = fileName,
                galleryName = galleryName,
            ))
        }
        images
    }
}