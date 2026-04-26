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
import coil3.size.Dimension
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHImageContentModeAspectFill
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy
import kotlin.coroutines.resume

/**
 * Coil Fetcher for loading iOS PHAsset thumbnails / images.
 *
 * Accepts URIs of the form `ph://localIdentifier`. For images, uses the binary asset
 * data path (preserves orientation). For VIDEO assets, uses requestImageForAsset which
 * extracts a representative frame — `requestImageDataAndOrientationForAsset` returns
 * nothing for videos.
 */
class PHAssetFetcher(
    private val data: String,
    private val options: Options,
) : Fetcher {

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override suspend fun fetch(): FetchResult? {
        val localIdentifier = data.removePrefix("ph://")

        val fetchResult = PHAsset.fetchAssetsWithLocalIdentifiers(
            listOf(localIdentifier),
            options = null,
        )
        if (fetchResult.count == 0uL) return null
        val asset = fetchResult.firstObject as? PHAsset ?: return null

        val isVideo = asset.mediaType == PHAssetMediaTypeVideo
        return if (isVideo) {
            fetchVideoFrame(asset)
        } else {
            fetchImageData(asset)
        }
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private suspend fun fetchImageData(asset: PHAsset): FetchResult? {
        val nsData = suspendCancellableCoroutine<NSData?> { continuation ->
            val requestOptions = PHImageRequestOptions().apply {
                setNetworkAccessAllowed(true)
                setDeliveryMode(PHImageRequestOptionsDeliveryModeHighQualityFormat)
                setSynchronous(false)
            }

            PHImageManager.defaultManager().requestImageDataAndOrientationForAsset(
                asset,
                options = requestOptions,
            ) { d, _, _, info ->
                if (continuation.isActive) {
                    val isDegraded = info?.get(platform.Photos.PHImageResultIsDegradedKey) as? Boolean ?: false
                    if (d != null) continuation.resume(d)
                    else if (!isDegraded) continuation.resume(null)
                }
            }
        } ?: return null

        return imageFromNSData(nsData)
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private suspend fun fetchVideoFrame(asset: PHAsset): FetchResult? {
        // Pick the best target size from the Coil request — falls back to a sane
        // thumbnail size if the layout hasn't measured yet.
        val targetSize = run {
            val w = (options.size.width as? Dimension.Pixels)?.px?.toDouble() ?: 640.0
            val h = (options.size.height as? Dimension.Pixels)?.px?.toDouble() ?: 640.0
            CGSizeMake(w, h)
        }

        val uiImage = suspendCancellableCoroutine<UIImage?> { continuation ->
            val requestOptions = PHImageRequestOptions().apply {
                setNetworkAccessAllowed(true)
                setDeliveryMode(PHImageRequestOptionsDeliveryModeHighQualityFormat)
                setSynchronous(false)
            }

            PHImageManager.defaultManager().requestImageForAsset(
                asset = asset,
                targetSize = targetSize,
                contentMode = PHImageContentModeAspectFill,
                options = requestOptions,
            ) { image, info ->
                if (continuation.isActive) {
                    val isDegraded = info?.get(platform.Photos.PHImageResultIsDegradedKey) as? Boolean ?: false
                    if (image != null) continuation.resume(image)
                    else if (!isDegraded) continuation.resume(null)
                }
            }
        } ?: return null

        // Convert UIImage → JPEG bytes → Skia Image. We pay one JPEG encode per frame
        // so the rest of the Coil pipeline can decode like any other image.
        val jpegData = UIImageJPEGRepresentation(uiImage, 0.85) ?: return null
        return imageFromNSData(jpegData)
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun imageFromNSData(nsData: NSData): FetchResult {
        val bytes = ByteArray(nsData.length.toInt())
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), nsData.bytes, nsData.length)
        }
        val skiaImage = org.jetbrains.skia.Image.makeFromEncoded(bytes)
        val imageBitmap = skiaImage.toComposeImageBitmap()
        return ImageFetchResult(
            image = imageBitmap.asSkiaBitmap().asImage(),
            isSampled = false,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<Any> {
        override fun create(
            data: Any,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            // Accept both Coil Uri and bare String models — the editor passes the URI
            // straight through (no HomebaseImageData wrapper) for pending local items.
            val uri = when (data) {
                is Uri -> data.toString()
                is String -> data
                else -> return null
            }
            if (!uri.startsWith("ph://") && !uri.contains("/L0/")) return null
            return PHAssetFetcher(uri, options)
        }
    }
}
