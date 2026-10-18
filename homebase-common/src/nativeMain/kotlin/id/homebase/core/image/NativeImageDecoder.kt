@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package id.homebase.core.image

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Foundation.dataWithBytes
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceShouldCacheImmediately
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.UIKit.UIGraphicsPopContext
import platform.UIKit.UIGraphicsPushContext
import platform.UIKit.UIImage
import org.jetbrains.skia.Image as SkiaImage

/**
 * Decodes image bytes (including HEIC/HEIF) to a Skia Image using iOS native APIs.
 *
 * Pipeline: ByteArray → NSData → UIImage → CGBitmapContext (BGRA) → Skia Image.
 * UIImage.drawInRect applies EXIF orientation automatically, so the returned
 * image is always orientation-normalized. No intermediate JPEG encoding.
 *
 * Follows the same CGBitmapContext → Skia pixel pattern used by PdfRenderer.
 */
object NativeImageDecoder {

    // Max pixel buffer size to prevent Int overflow in bytesPerRow * height.
    // ~512 MP (e.g. 23170×23170) — well above any real camera output.
    private const val MAX_PIXEL_BYTES = Int.MAX_VALUE.toLong()

    fun decode(imageBytes: ByteArray): SkiaImage? {
        if (imageBytes.isEmpty()) return null

        val nsData = imageBytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), imageBytes.size.toULong())
        }
        return decode(nsData)
    }

    fun decode(nsData: NSData): SkiaImage? {
        val uiImage = UIImage.imageWithData(nsData) ?: return null
        return decodeUIImage(uiImage)
    }

    /**
     * Decodes straight to an orientation-normalized image whose longer edge is at most
     * [maxPixelSize], so the full-resolution bitmap is never allocated.
     */
    fun decodeThumbnail(imageBytes: ByteArray, maxPixelSize: Int): SkiaImage? {
        if (imageBytes.isEmpty() || maxPixelSize <= 0) return null
        val nsData = imageBytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), imageBytes.size.toULong())
        }
        @Suppress("UNCHECKED_CAST")
        val cfData = CFBridgingRetain(nsData) as CFDataRef? ?: return null
        try {
            val source = CGImageSourceCreateWithData(cfData, null) ?: return null
            try {
                val options = CFDictionaryCreateMutable(
                    null, 4, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
                ) ?: return null
                val maxSize = CFBridgingRetain(NSNumber(int = maxPixelSize))
                CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
                CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
                CFDictionarySetValue(options, kCGImageSourceShouldCacheImmediately, kCFBooleanTrue)
                CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, maxSize)
                val cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0u, options)
                CFRelease(maxSize)
                CFRelease(options)
                cgImage ?: return null
                try {
                    val width = CGImageGetWidth(cgImage).toInt()
                    val height = CGImageGetHeight(cgImage).toInt()
                    return rasterize(width, height) { context ->
                        CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), cgImage)
                    }
                } finally {
                    CGImageRelease(cgImage)
                }
            } finally {
                CFRelease(source)
            }
        } finally {
            CFRelease(cfData)
        }
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

        val colorSpace = CGColorSpaceCreateDeviceRGB()
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
            imageInfo = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL),
            bytes = pixelData,
            rowBytes = bytesPerRow.toInt(),
        )
    }
}
