@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package id.homebase.chat.services.image

import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.createCGImage
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageOrientation
import platform.UIKit.UIImagePNGRepresentation
import platform.Vision.VNGenerateForegroundInstanceMaskRequest
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNInstanceMaskObservation
import platform.posix.memcpy

private const val TAG = "BackgroundRemover"

/**
 * iOS implementation: Vision `VNGenerateForegroundInstanceMaskRequest` (iOS 17+,
 * class-agnostic foreground instance mask; the app targets iOS 18.2 so the
 * availability is fully covered).
 *
 * Pipeline: bytes → UIImage → upright CGImage → VNImageRequestHandler →
 * VNInstanceMaskObservation.generateMaskedImageOfInstances → CVPixelBuffer →
 * CIImage → CGImage → UIImage → PNG (alpha preserved).
 *
 * HARD CAVEAT: the foreground-instance segmenter runs on the Neural Engine and is
 * a no-op on the Simulator / CPU-only devices — it surfaces here as an empty
 * observation list, which we map to null. Requires physical-device testing.
 *
 * NOTE (deliberate platform asymmetry): unlike the Android actual — which caps the
 * segmenter input via `inSampleSize` to bound peak memory on low-RAM devices — iOS feeds
 * the full-resolution CGImage to Vision. iOS gives apps generous memory and we target
 * modern devices (18.2), so the transient full-res bitmap is acceptable here; the
 * uploaded cut-out is still downscaled to a sticker size by the shared
 * [id.homebase.chat.services.image.StickerImageProcessor]. Add ImageIO thumbnail
 * decoding here if a low-RAM iOS OOM ever shows up in the field.
 *
 * Returns null (soft fail) when the source can't be decoded, the request errors,
 * or no confident subject instance is found.
 */
actual suspend fun removeBackground(srcBytes: ByteArray): ByteArray? = withContext(Dispatchers.Default) {
    if (srcBytes.isEmpty()) return@withContext null

    val nsData: NSData = srcBytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = srcBytes.size.toULong())
    }
    val uiImage = UIImage.imageWithData(nsData) ?: run {
        Logger.w(tag = TAG) { "iOS: could not decode source bytes" }
        return@withContext null
    }
    // UIImage parses EXIF orientation into imageOrientation, but `.CGImage` hands back the
    // raw, unrotated pixel buffer — feeding that to Vision yields a sideways cut-out for
    // camera photos / HEIC. Bake the orientation into an upright image first.
    val cgImage = uiImage.uprightImage().CGImage ?: run {
        Logger.w(tag = TAG) { "iOS: UIImage has no CGImage backing" }
        return@withContext null
    }

    try {
        val request = VNGenerateForegroundInstanceMaskRequest()
        val handler = VNImageRequestHandler(cGImage = cgImage, options = emptyMap<Any?, Any?>())

        val performed = memScoped {
            val errVar = alloc<kotlinx.cinterop.ObjCObjectVar<NSError?>>()
            val ok = handler.performRequests(listOf(request), errVar.ptr)
            if (!ok) {
                Logger.w(tag = TAG) { "iOS: performRequests failed: ${errVar.value?.localizedDescription}" }
            }
            ok
        }
        if (!performed) return@withContext null

        val observation = (request.results?.firstOrNull() as? VNInstanceMaskObservation) ?: run {
            // Empty on Simulator / no confident subject.
            Logger.d(tag = TAG) { "iOS: no foreground instance mask observation" }
            return@withContext null
        }

        val maskedPixelBuffer = memScoped {
            val errVar = alloc<kotlinx.cinterop.ObjCObjectVar<NSError?>>()
            val buffer = observation.generateMaskedImageOfInstances(
                instances = observation.allInstances,
                fromRequestHandler = handler,
                croppedToInstancesExtent = false,
                error = errVar.ptr,
            )
            if (buffer == null) {
                Logger.w(tag = TAG) { "iOS: generateMaskedImageOfInstances failed: ${errVar.value?.localizedDescription}" }
            }
            buffer
        } ?: return@withContext null

        // CVPixelBuffer (BGRA with alpha) → CIImage → CGImage → UIImage → PNG.
        val ciImage = CIImage.imageWithCVPixelBuffer(maskedPixelBuffer)
        val ciContext = CIContext.context()
        val outCgImage = ciContext.createCGImage(ciImage, fromRect = ciImage.extent)
            ?: run {
                Logger.w(tag = TAG) { "iOS: could not rasterize masked CIImage" }
                return@withContext null
            }
        val outImage = UIImage.imageWithCGImage(outCgImage)
        val png = UIImagePNGRepresentation(outImage) ?: run {
            Logger.w(tag = TAG) { "iOS: PNG encode of cut-out failed" }
            return@withContext null
        }
        png.toByteArray()
    } catch (e: Exception) {
        Logger.w(tag = TAG) { "iOS: background removal threw: ${e.message}" }
        null
    }
}

/**
 * Capability probe. The Vision foreground-instance segmenter exists from iOS 17+
 * (the app targets 18.2, so the class is always linkable) but only produces a
 * result on the Neural Engine — it yields nothing on the Simulator. We report
 * `true` here (the API is present); a device without a confident result still
 * degrades to a null from [removeBackground].
 */
actual fun isBackgroundRemovalSupported(): Boolean = true

/**
 * No-op on iOS: the Vision foreground-instance segmenter is part of the OS, so there
 * is no optional model to pre-download (unlike Android's ML Kit module that rides on
 * Google Play services).
 */
actual fun warmUpBackgroundRemoval() {
    // Nothing to warm up — Vision ships with the OS.
}

/**
 * Return an upright copy of this image. [UIImage.CGImage] exposes the raw, unrotated
 * pixel buffer regardless of [UIImage.imageOrientation], so for any non-`.up` orientation
 * (camera / HEIC portrait frames) we redraw — which applies the orientation — and return
 * the upright result. Returns the receiver unchanged when already upright, or if the
 * redraw fails. Runs off-main; the UIGraphics image-context stack is thread-local/safe.
 */
private fun UIImage.uprightImage(): UIImage {
    if (imageOrientation == UIImageOrientation.UIImageOrientationUp) return this
    val (width, height) = size.useContents { width to height }
    UIGraphicsBeginImageContextWithOptions(size, false, scale)
    drawInRect(CGRectMake(0.0, 0.0, width, height))
    val upright = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return upright ?: this
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, length) }
    return out
}
