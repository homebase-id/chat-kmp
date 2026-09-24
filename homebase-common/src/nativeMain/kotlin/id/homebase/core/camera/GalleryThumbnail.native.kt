@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import id.homebase.core.gallery.photoLibraryReadAuthorized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSSortDescriptor
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHFetchOptions
import platform.Photos.PHImageContentModeAspectFill
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.Photos.PHImageRequestOptionsResizeModeFast
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy
import kotlin.coroutines.resume

// Never registers a PHPhotoLibrary change observer: registering one raises the Photos prompt by itself.
@Composable
internal actual fun rememberLatestGalleryThumbnail(sizePx: Int): ImageBitmap? =
    produceState<ImageBitmap?>(null, sizePx) {
        if (!photoLibraryReadAuthorized()) return@produceState
        val asset = withContext(Dispatchers.Default) { newestAsset() } ?: return@produceState
        val image = requestThumbnail(asset, sizePx) ?: return@produceState
        value = withContext(Dispatchers.Default) { image.toImageBitmap() }
    }.value

private fun newestAsset(): PHAsset? {
    val options = PHFetchOptions().apply {
        sortDescriptors = listOf(NSSortDescriptor("creationDate", false))
        fetchLimit = 1u
    }
    return listOf(PHAssetMediaTypeImage, PHAssetMediaTypeVideo)
        .mapNotNull { PHAsset.fetchAssetsWithMediaType(it, options).firstObject as? PHAsset }
        .maxByOrNull { it.creationDate?.timeIntervalSince1970 ?: 0.0 }
}

private suspend fun requestThumbnail(asset: PHAsset, sizePx: Int): UIImage? =
    suspendCancellableCoroutine { continuation ->
        val options = PHImageRequestOptions().apply {
            setNetworkAccessAllowed(false)
            setDeliveryMode(PHImageRequestOptionsDeliveryModeHighQualityFormat)
            setResizeMode(PHImageRequestOptionsResizeModeFast)
        }
        val manager = PHImageManager.defaultManager()
        val request = manager.requestImageForAsset(
            asset = asset,
            targetSize = CGSizeMake(sizePx.toDouble(), sizePx.toDouble()),
            contentMode = PHImageContentModeAspectFill,
            options = options,
        ) { image, _ -> if (continuation.isActive) continuation.resume(image) }
        continuation.invokeOnCancellation { manager.cancelImageRequest(request) }
    }

private fun UIImage.toImageBitmap(): ImageBitmap? {
    val data = UIImageJPEGRepresentation(this, 0.9) ?: return null
    val bytes = ByteArray(data.length.toInt())
    bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return Image.makeFromEncoded(bytes).toComposeImageBitmap()
}
