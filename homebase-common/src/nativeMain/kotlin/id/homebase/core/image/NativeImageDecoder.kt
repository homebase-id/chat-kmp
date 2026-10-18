@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package id.homebase.core.image

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFURLRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFNumberRef
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFNumberIntType
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
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImagePropertyOrientation
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceCreateWithURL
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
     * Decodes straight to an orientation-normalized image whose longer edge is at most the
     * value [maxPixelSizeFor] returns for the upright source size, so the full-resolution
     * bitmap is never allocated.
     */
    fun decodeThumbnail(imageBytes: ByteArray, maxPixelSizeFor: (width: Int, height: Int) -> Int): SkiaImage? {
        if (imageBytes.isEmpty()) return null
        val nsData = imageBytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), imageBytes.size.toULong())
        }
        @Suppress("UNCHECKED_CAST")
        val cfData = CFBridgingRetain(nsData) as CFDataRef? ?: return null
        try {
            val source = CGImageSourceCreateWithData(cfData, null) ?: return null
            return decodeThumbnail(source, maxPixelSizeFor)
        } finally {
            CFRelease(cfData)
        }
    }

    /** As above, but ImageIO reads the file at [path] in place instead of a copy of its bytes. */
    fun decodeThumbnail(path: String, maxPixelSizeFor: (width: Int, height: Int) -> Int): SkiaImage? {
        @Suppress("UNCHECKED_CAST")
        val url = CFBridgingRetain(NSURL.fileURLWithPath(path)) as CFURLRef? ?: return null
        try {
            val source = CGImageSourceCreateWithURL(url, null) ?: return null
            return decodeThumbnail(source, maxPixelSizeFor)
        } finally {
            CFRelease(url)
        }
    }

    private fun decodeThumbnail(source: CGImageSourceRef, maxPixelSizeFor: (width: Int, height: Int) -> Int): SkiaImage? {
        try {
            val (width, height) = orientedPixelSize(source) ?: return null
            val maxPixelSize = maxPixelSizeFor(width, height)
            if (maxPixelSize <= 0) return null
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
                val thumbWidth = CGImageGetWidth(cgImage).toInt()
                val thumbHeight = CGImageGetHeight(cgImage).toInt()
                return rasterize(thumbWidth, thumbHeight) { context ->
                    CGContextDrawImage(
                        context,
                        CGRectMake(0.0, 0.0, thumbWidth.toDouble(), thumbHeight.toDouble()),
                        cgImage,
                    )
                }
            } finally {
                CGImageRelease(cgImage)
            }
        } finally {
            CFRelease(source)
        }
    }

    // CF lookups, not NSDictionary.objectForKey: a CFStringRef key doesn't bridge to NSString in K/N.
    private fun orientedPixelSize(source: CGImageSourceRef): Pair<Int, Int>? {
        val props = CGImageSourceCopyPropertiesAtIndex(source, 0u, null) ?: return null
        try {
            val width = cfInt(props, kCGImagePropertyPixelWidth) ?: return null
            val height = cfInt(props, kCGImagePropertyPixelHeight) ?: return null
            val quarterTurn = cfInt(props, kCGImagePropertyOrientation) in 5..8
            return if (quarterTurn) height to width else width to height
        } finally {
            CFRelease(props)
        }
    }

    private fun cfInt(dict: CFDictionaryRef, key: CFStringRef?): Int? = memScoped {
        val number: CFNumberRef = CFDictionaryGetValue(dict, key)?.reinterpret() ?: return null
        val out = alloc<IntVar>()
        if (CFNumberGetValue(number, kCFNumberIntType, out.ptr)) out.value else null
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
