package id.homebase.core.gallery

import io.github.vinceglb.filekit.PlatformFile
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSSortDescriptor
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAssetResource
import platform.Photos.PHFetchOptions

class IOSGalleryManager : PlatformGalleryManager {
    override suspend fun fetchGalleryImages(limit: Int): List<GalleryImage> =
        withContext(Dispatchers.Default) {
            val fetchOptions = PHFetchOptions().apply {
                sortDescriptors = listOf(NSSortDescriptor("creationDate", false))
                fetchLimit = limit.toULong()
            }

            val images = processFetchResult(
                PHAsset.fetchAssetsWithMediaType(PHAssetMediaTypeImage, fetchOptions),
                "image/*",
            )
            val videos = processFetchResult(
                PHAsset.fetchAssetsWithMediaType(PHAssetMediaTypeVideo, fetchOptions),
                "video/*",
            )

            (images + videos)
                .sortedByDescending { it.dateAdded }
                .take(limit)
        }

    private fun processFetchResult(
        fetchResult: platform.Photos.PHFetchResult,
        mimeType: String,
    ): List<GalleryImage> {
        val items = ArrayList<GalleryImage>(fetchResult.count.toInt())

        for (i in 0 until fetchResult.count.toInt()) {
            val asset = fetchResult.objectAtIndex(i.toULong()) as PHAsset

            val resources = PHAssetResource.assetResourcesForAsset(asset)
            val fileName = if (resources.isNotEmpty()) {
                (resources.first() as? PHAssetResource)?.originalFilename ?: "unknown"
            } else {
                "unknown"
            }

            val isVideo = asset.mediaType == PHAssetMediaTypeVideo
            val durationMs = if (isVideo && asset.duration > 0.0) {
                (asset.duration * 1000.0).roundToLong()
            } else null

            items.add(
                GalleryImage(
                    id = asset.localIdentifier,
                    file = PlatformFile(asset.localIdentifier),
                    thumbnailUri = "ph://${asset.localIdentifier}",
                    dateAdded = asset.creationDate?.timeIntervalSince1970?.toLong() ?: 0L,
                    mimeType = mimeType,
                    fileName = fileName,
                    // Album-membership lookup (PHAssetCollection.fetchAssetCollectionsContainingAsset)
                    // used to run here per-asset — that was 50× a slow Photos round-trip on every
                    // picker open and the value wasn't displayed anywhere. Drop it.
                    galleryName = "",
                    durationMs = durationMs,
                    width = asset.pixelWidth.toInt(),
                    height = asset.pixelHeight.toInt(),
                )
            )
        }

        return items
    }
}
