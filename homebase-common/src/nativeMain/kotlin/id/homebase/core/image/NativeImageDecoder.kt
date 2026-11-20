@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.image

import co.touchlab.kermit.Logger
import id.homebase.api.image.ImageIoDecode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.ImageIO.CGImageSourceRef
import platform.UIKit.UIGraphicsPopContext
import platform.UIKit.UIGraphicsPushContext
import platform.UIKit.UIImage
import org.jetbrains.skia.Image as SkiaImage

/**
 * Decodes images (including HEIC/HEIF) to orientation-normalized Skia Images through [ImageIoDecode], the same
 * decode uploads use, so a Display P3 photo shows the same colours everywhere.
 */
object NativeImageDecoder {

    // Max pixel buffer size to prevent Int overflow in bytesPerRow * height.
    // ~512 MP (e.g. 23170×23170) — well above any real camera output.
    private const val MAX_PIXEL_BYTES = Int.MAX_VALUE.toLong()

    /**
     * Decodes straight to an orientation-normalized image whose longer edge is at most the
     * value [maxPixelSizeFor] returns for the upright source size, so the full-resolution
     * bitmap is never allocated.
     */
    fun decodeThumbnail(imageBytes: ByteArray, maxPixelSizeFor: (width: Int, height: Int) -> Int): SkiaImage? =
        ImageIoDecode.open(imageBytes)?.let { decodeThumbnail(it, maxPixelSizeFor) }

    /** As above, but ImageIO reads the file at [path] in place instead of a copy of its bytes. */
    fun decodeThumbnail(path: String, maxPixelSizeFor: (width: Int, height: Int) -> Int): SkiaImage? =
        ImageIoDecode.open(path)?.let { decodeThumbnail(it, maxPixelSizeFor) }

    private fun decodeThumbnail(source: CGImageSourceRef, maxPixelSizeFor: (width: Int, height: Int) -> Int): SkiaImage? = try {
        ImageIoDecode.displayedSize(source)
            ?.let { maxPixelSizeFor(it.pixelWidth, it.pixelHeight) }
            ?.takeIf { it > 0 }
            ?.let { ImageIoDecode.decode(source, it) }
    } catch (e: IllegalStateException) {
        // Null, as before the decode was shared, so Coil falls back to its next decoder instead of failing the request.
        Logger.w(tag = "NativeImageDecoder", throwable = e) { "ImageIO couldn't decode the image" }
        null
    } finally {
        CFRelease(source)
    }

    fun decodeUIImage(uiImage: UIImage): SkiaImage? {
        val width: Int
        val height: Int
        uiImage.size.useContents {
            width = (this.width * uiImage.scale).toInt()
            height = (this.height * uiImage.scale).toInt()
        }
        return rasterize(width, height) { context ->
            // CG uses bottom-left origin; flip to UIKit's top-left convention
            // so UIImage.drawInRect applies orientation correctly
            CGContextTranslateCTM(context, 0.0, height.toDouble())
            CGContextScaleCTM(context, 1.0, -1.0)

            UIGraphicsPushContext(context)
            uiImage.drawInRect(CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
            UIGraphicsPopContext()
        }
    }

    private inline fun rasterize(width: Int, height: Int, draw: (CGContextRef) -> Unit): SkiaImage? {
        if (width <= 0 || height <= 0) return null

        val bytesPerRow = width.toLong() * 4
        if (bytesPerRow * height.toLong() > MAX_PIXEL_BYTES) return null
        val pixelData = ByteArray(bytesPerRow.toInt() * height)

        val colorSpace = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
        try {
            pixelData.usePinned { pinned ->
                val context = CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = width.toULong(),
                    height = height.toULong(),
                    bitsPerComponent = 8u,
                    bytesPerRow = bytesPerRow.toULong(),
                    space = colorSpace,
                    bitmapInfo = CG_BGRA_PREMUL_BITMAP_INFO,
                ) ?: return null
                try {
                    draw(context)
                } finally {
                    CGContextRelease(context)
                }
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }

        return SkiaImage.makeRaster(
            imageInfo = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB),
            bytes = pixelData,
            rowBytes = bytesPerRow.toInt(),
        )
    }
}
