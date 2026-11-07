@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.api.image

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringRef
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceIsWideGamutRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetColorSpace
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGColorSpaceDisplayP3
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSNumber
import platform.Foundation.dataWithBytes
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImagePropertyOrientation
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceShouldCacheImmediately
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize

/**
 * ImageIO decodes HEIC and other camera formats directly and only as large as asked, with no JPEG
 * bridge. It hands over to Skia's bitmap for scaling and encoding, so those match the other targets.
 * [SkiaImageSource] takes over for anything ImageIO can't read.
 */
private class ImageIoSource(private val source: CGImageSourceRef, private val displayed: ImageSize) : ImageSource {
    override val displayedSize get() = displayed

    override fun decode(minLongEdge: Int): PipelineBitmap {
        val options = mapOf<Any?, Any?>(
            keyOf(kCGImageSourceCreateThumbnailFromImageAlways) to true,
            keyOf(kCGImageSourceCreateThumbnailWithTransform) to true,
            keyOf(kCGImageSourceShouldCacheImmediately) to true,
            keyOf(kCGImageSourceThumbnailMaxPixelSize) to minLongEdge,
        )
        @Suppress("UNCHECKED_CAST")
        val cfOptions = CFBridgingRetain(options) as CFDictionaryRef?
        val cgImage = try {
            CGImageSourceCreateThumbnailAtIndex(source, 0.convert(), cfOptions)
        } finally {
            cfOptions?.let { CFRelease(it) }
        } ?: throw IllegalArgumentException("ImageIO couldn't decode the image")
        try {
            return SkiaPipelineBitmap(cgImage.toSkiaImage())
        } finally {
            CGImageRelease(cgImage)
        }
    }

    override fun close() = CFRelease(source)
}

private fun keyOf(key: CFStringRef?): Any? = CFBridgingRelease(CFRetain(key))

// Drawing into a context of the matching colour space keeps P3 pixels P3 for Skia to carry on.
private fun CGImageRef.toSkiaImage(): Image {
    val width = CGImageGetWidth(this).toInt()
    val height = CGImageGetHeight(this).toInt()
    val wide = CGImageGetColorSpace(this)?.let { CGColorSpaceIsWideGamutRGB(it) } == true
    val colorSpace = CGColorSpaceCreateWithName(if (wide) kCGColorSpaceDisplayP3 else kCGColorSpaceSRGB)
    val rowBytes = width * 4
    val pixels = ByteArray(rowBytes * height)
    try {
        pixels.usePinned { pinned ->
            val context = CGBitmapContextCreate(
                pinned.addressOf(0),
                width.convert(),
                height.convert(),
                8.convert(),
                rowBytes.convert(),
                colorSpace,
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
            ) ?: throw IllegalStateException("Couldn't create a bitmap context")
            try {
                CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), this)
            } finally {
                CGContextRelease(context)
            }
        }
    } finally {
        CGColorSpaceRelease(colorSpace)
    }
    val info = ImageInfo(
        width,
        height,
        ColorType.RGBA_8888,
        ColorAlphaType.PREMUL,
        if (wide) ColorSpace.displayP3 else ColorSpace.sRGB,
    )
    return Image.makeRaster(info, pixels, rowBytes)
}

private fun openWithImageIo(bytes: ByteArray): ImageSource? {
    if (bytes.isEmpty()) return null
    val nsData = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
    @Suppress("UNCHECKED_CAST")
    val cfData = CFBridgingRetain(nsData) as CFDataRef? ?: return null
    val source = try {
        CGImageSourceCreateWithData(cfData, null)
    } finally {
        CFRelease(cfData)
    } ?: return null
    val displayed = displayedSizeOf(source)
    if (displayed == null) {
        CFRelease(source)
        return null
    }
    return ImageIoSource(source, displayed)
}

private fun displayedSizeOf(source: CGImageSourceRef): ImageSize? {
    val cfProps = CGImageSourceCopyPropertiesAtIndex(source, 0.convert(), null) ?: return null
    val props = CFBridgingRelease(cfProps) as? NSDictionary ?: return null
    val width = (props.objectForKey(kCGImagePropertyPixelWidth) as? NSNumber)?.intValue ?: return null
    val height = (props.objectForKey(kCGImagePropertyPixelHeight) as? NSNumber)?.intValue ?: return null
    val orientation = (props.objectForKey(kCGImagePropertyOrientation) as? NSNumber)?.intValue ?: 1
    return if (orientation in 5..8) ImageSize(height, width) else ImageSize(width, height)
}

internal actual fun openImageSource(bytes: ByteArray): ImageSource =
    openWithImageIo(bytes) ?: SkiaImageSource(bytes)
