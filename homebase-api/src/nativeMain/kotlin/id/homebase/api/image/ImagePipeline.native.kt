@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.api.image

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFNumberRef
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceIsWideGamutRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetAlphaInfo
import platform.CoreGraphics.CGImageGetColorSpace
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGColorSpaceDisplayP3
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
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
private class ImageIoSource(
    private val source: CGImageSourceRef,
    private val bytes: ByteArray,
    override val displayedSize: ImageSize,
) : ImageSource {
    override fun decode(minLongEdge: Int): PipelineBitmap {
        val options = CFDictionaryCreateMutable(null, 4, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            ?: throw IllegalStateException("Couldn't create decode options")
        // ImageIO aliases on reductions under 2x and can land a pixel off the plan, so only the memory guard forces one.
        val longEdge = max(displayedSize.pixelWidth, displayedSize.pixelHeight)
        val guarded = memoryGuardScale(displayedSize, minLongEdge)?.let { ceil(longEdge * it).toInt() } ?: Int.MAX_VALUE
        val coarse = if (longEdge >= minLongEdge.toLong() * 2) minOf(minLongEdge * 2, (longEdge + 1) / 2) else longEdge
        val coarseEdge = minOf(coarse, guarded)
        val maxSize = CFBridgingRetain(NSNumber(int = coarseEdge))
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
        CFDictionarySetValue(options, kCGImageSourceShouldCacheImmediately, kCFBooleanTrue)
        CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, maxSize)
        val cgImage = try {
            CGImageSourceCreateThumbnailAtIndex(source, 0.convert(), options)
        } finally {
            CFRelease(maxSize)
            CFRelease(options)
        } ?: return SkiaImageSource(bytes).use { it.decode(minLongEdge) }
        try {
            return SkiaPipelineBitmap(cgImage.toSkiaImage())
        } finally {
            CGImageRelease(cgImage)
        }
    }

    override fun close() = CFRelease(source)
}

// Drawing into a context of the matching colour space keeps P3 pixels P3 for Skia to carry on.
private fun CGImageRef.toSkiaImage(): Image {
    val width = CGImageGetWidth(this).toInt()
    val height = CGImageGetHeight(this).toInt()
    val wide = CGImageGetColorSpace(this)?.let { CGColorSpaceIsWideGamutRGB(it) } == true
    val opaque = CGImageGetAlphaInfo(this) in OPAQUE_ALPHA_INFO
    val colorSpace = CGColorSpaceCreateWithName(if (wide) kCGColorSpaceDisplayP3 else kCGColorSpaceSRGB)
    require(width.toLong() * 4 * height <= Int.MAX_VALUE) { "$width x $height is too large to decode" }
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
        if (opaque) ColorAlphaType.OPAQUE else ColorAlphaType.PREMUL,
        if (wide) ColorSpace.displayP3 else ColorSpace.sRGB,
    )
    return Image.makeRaster(info, pixels, rowBytes)
}

private val OPAQUE_ALPHA_INFO = setOf(
    CGImageAlphaInfo.kCGImageAlphaNone,
    CGImageAlphaInfo.kCGImageAlphaNoneSkipLast,
    CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst,
)

private fun openWithImageIo(bytes: ByteArray): ImageSource? {
    if (bytes.isEmpty()) return null
    val nsData = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
    val cfData: CFDataRef = CFBridgingRetain(nsData)?.reinterpret() ?: return null
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
    return ImageIoSource(source, bytes, displayed)
}

// CF lookups, not NSDictionary.objectForKey: a CFStringRef key doesn't bridge to NSString in K/N.
private fun displayedSizeOf(source: CGImageSourceRef): ImageSize? {
    val props = CGImageSourceCopyPropertiesAtIndex(source, 0.convert(), null) ?: return null
    try {
        val width = cfInt(props, kCGImagePropertyPixelWidth) ?: return null
        val height = cfInt(props, kCGImagePropertyPixelHeight) ?: return null
        val swapped = jpegOrientationSwapsDimensions(cfInt(props, kCGImagePropertyOrientation) ?: 1)
        return if (swapped) ImageSize(height, width) else ImageSize(width, height)
    } finally {
        CFRelease(props)
    }
}

private fun cfInt(dict: CFDictionaryRef, key: CFStringRef?): Int? = memScoped {
    val number: CFNumberRef = CFDictionaryGetValue(dict, key)?.reinterpret() ?: return null
    val out = alloc<IntVar>()
    if (CFNumberGetValue(number, kCFNumberIntType, out.ptr)) out.value else null
}

// About 96 MB of pixels. A 12 MP photo stays a full, clean decode; a 48 MP one doesn't.
private const val FULL_DECODE_PIXEL_LIMIT = 24_000_000L

/**
 * How far to shrink a decode that would otherwise pass [FULL_DECODE_PIXEL_LIMIT], or null to leave
 * it alone. A memory guard, not a quality step: it never goes below what the largest output needs.
 */
private fun memoryGuardScale(size: ImageSize, minLongEdge: Int): Double? {
    val pixels = size.pixelWidth.toDouble() * size.pixelHeight
    val longEdge = max(size.pixelWidth, size.pixelHeight)
    if (pixels <= FULL_DECODE_PIXEL_LIMIT || longEdge <= minLongEdge) return null
    val smallest = minLongEdge.toDouble() / longEdge
    return max(smallest, min(sqrt(FULL_DECODE_PIXEL_LIMIT / pixels), smallest * 2))
}

internal actual fun openImageSource(bytes: ByteArray): ImageSource =
    openWithImageIo(bytes) ?: SkiaImageSource(bytes)
