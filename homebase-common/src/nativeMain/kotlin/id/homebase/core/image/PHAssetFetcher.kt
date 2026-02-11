package id.homebase.core.image

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Photos.PHAsset
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.posix.memcpy
import kotlin.coroutines.resume

/**
 * Coil Fetcher for loading iOS PHAsset images using the Photos framework.
 *
 * This fetcher handles URIs with the format: "ph://localIdentifier"
 * where localIdentifier is the PHAsset's localIdentifier string.
 */
class PHAssetFetcher(
    private val data: String,
    private val options: Options
) : Fetcher {

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override suspend fun fetch(): FetchResult? {
        // Extract the local identifier from the URI
        println("PHAssetFetcher.fetch() called with data: $data")
        val localIdentifier = if (data.startsWith("ph://")) {
            data.removePrefix("ph://")
        } else {
            data
        }

        // Fetch the PHAsset
        val fetchResult = PHAsset.fetchAssetsWithLocalIdentifiers(
            listOf(localIdentifier),
            options = null
        )

        if (fetchResult.count == 0uL) {
            return null
        }

        val asset = fetchResult.firstObject as? PHAsset ?: return null

        // Request the image from PHImageManager
        val imageData = suspendCancellableCoroutine { continuation ->
            val requestOptions = PHImageRequestOptions().apply {
                setNetworkAccessAllowed(true)
                setResizeMode(PHImageRequestOptionsDeliveryModeHighQualityFormat)
                setSynchronous(false)
            }

            PHImageManager.defaultManager().requestImageDataAndOrientationForAsset(
                asset,
                options = requestOptions
            ) { data, _, _, info ->
                if (continuation.isActive) {
                    val isDegraded = info?.get(platform.Photos.PHImageResultIsDegradedKey) as? Boolean ?: false

                    // On a physical device, this might be called with a null 'data'
                    // first if the asset is downloading from iCloud.
                    if (data != null) {
                        continuation.resume(data)
                    } else if (!isDegraded) {
                        // If it's not degraded and data is still null, the fetch failed.
                        continuation.resume(null)
                    }
                }
            }
        }

        if (imageData == null) {
            return null
        }

        // Convert NSData to ByteArray
        val bytes = ByteArray(imageData.length.toInt())
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), imageData.bytes, imageData.length)
        }

        // Convert ByteArray to Skia Image to ImageBitmap to Coil Image
        val skiaImage = org.jetbrains.skia.Image.makeFromEncoded(bytes)
        val imageBitmap = skiaImage.toComposeImageBitmap()

        return ImageFetchResult(
            image = imageBitmap.asSkiaBitmap().asImage(),
            isSampled = false,
            dataSource = DataSource.DISK
        )
    }

    class Factory : Fetcher.Factory<Any> {
        override fun create(
            data: Any,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            if (data !is Uri) {
                return null
            }
            val uri = data.toString()
            // Only handle URIs starting with "ph://" or PHAsset localIdentifiers
            if (!uri.startsWith("ph://") && !uri.contains("/L0/")) {
                return null
            }
            return PHAssetFetcher(uri, options)
        }
    }
}


