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
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAssetResource
import platform.Photos.PHFetchOptions

class IOSGalleryManager: PlatformGalleryManager {
    override suspend fun fetchGalleryImages(limit: Int): List<GalleryImage> = withContext(Dispatchers.Main) {
          val fetchOptions = PHFetchOptions().apply {
            sortDescriptors = listOf(
                NSSortDescriptor("creationDate", false)
            )
            fetchLimit = limit.toULong()
        }

        // Fetch images
        val imageFetchResult = PHAsset.fetchAssetsWithMediaType(
            PHAssetMediaTypeImage,
            fetchOptions
        )

        // Fetch videos
        val videoFetchResult = PHAsset.fetchAssetsWithMediaType(
            PHAssetMediaTypeVideo,
            fetchOptions
        )

        // Process images
        val images = processFetchResult(imageFetchResult, "image/*")

        // Process videos
        val videos = processFetchResult(videoFetchResult, "video/*")

        // Combine and sort by date, then take the limit
        (images + videos)
            .sortedByDescending { it.dateAdded }
            .take(limit)
    }

    private fun processFetchResult(fetchResult: platform.Photos.PHFetchResult, mimeType: String): List<GalleryImage> {
        val items = mutableListOf<GalleryImage>()

        for (i in 0 until fetchResult.count.toInt()) {
            val asset = fetchResult.objectAtIndex(i.toULong()) as PHAsset

            // Get the file name from asset resources
            val resources = PHAssetResource.assetResourcesForAsset(asset)
            val fileName = if (resources.isNotEmpty()) {
                (resources.first() as? PHAssetResource)?.originalFilename ?: "unknown"
            } else {
                "unknown"
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

            items.add(GalleryImage(
                id = asset.localIdentifier,
                file = PlatformFile(asset.localIdentifier),
                thumbnailUri = "ph://${asset.localIdentifier}",
                dateAdded = asset.creationDate?.timeIntervalSince1970?.toLong() ?: 0L,
                mimeType = mimeType,
                fileName = fileName,
                galleryName = galleryName,
            ))
        }

        return items
    }
}