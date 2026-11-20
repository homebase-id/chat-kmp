@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.api.image

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFNumberRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFURLRef
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
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
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.ImageIO.CGImageMetadataCreateXMPData
import platform.ImageIO.CGImageMetadataRef
import platform.ImageIO.CGImageSourceCopyAuxiliaryDataInfoAtIndex
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImageAuxiliaryDataInfoData
import platform.ImageIO.kCGImageAuxiliaryDataInfoDataDescription
import platform.ImageIO.kCGImageAuxiliaryDataInfoMetadata
import platform.ImageIO.kCGImageAuxiliaryDataTypeHDRGainMap
import platform.ImageIO.kCGImageAuxiliaryDataTypeISOGainMap
import platform.ImageIO.kCGImagePropertyMakerAppleDictionary
import platform.ImageIO.kCGImagePropertyOrientation
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceDecodeRequest
import platform.ImageIO.kCGImageSourceDecodeToHDR
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceShouldCacheImmediately
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize

/**
 * The one ImageIO decode on iOS, for uploads and for what the app shows: upright, at most a given long edge, and in
 * the source's colour space (Display P3 stays P3). The sources it opens are the caller's to release.
 */
object ImageIoDecode {
    fun open(bytes: ByteArray): CGImageSourceRef? {
        if (bytes.isEmpty()) return null
        val nsData = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        val cfData: CFDataRef = CFBridgingRetain(nsData)?.reinterpret() ?: return null
        return try {
            CGImageSourceCreateWithData(cfData, null)
        } finally {
            CFRelease(cfData)
        }
    }

    fun open(path: String): CGImageSourceRef? {
        val cfUrl: CFURLRef = CFBridgingRetain(NSURL.fileURLWithPath(path))?.reinterpret() ?: return null
        return try {
            CGImageSourceCreateWithURL(cfUrl, null)
        } finally {
            CFRelease(cfUrl)
        }
    }

    /** The size after EXIF rotation, read without decoding. */
    fun displayedSize(source: CGImageSourceRef): ImageSize? = withProperties(source) { props ->
        val width = cfInt(props, kCGImagePropertyPixelWidth) ?: return@withProperties null
        val height = cfInt(props, kCGImagePropertyPixelHeight) ?: return@withProperties null
        if (jpegOrientationSwapsDimensions(cfInt(props, kCGImagePropertyOrientation) ?: 1)) ImageSize(height, width) else ImageSize(width, height)
    }

    /** Null where ImageIO can't decode it. */
    fun decode(source: CGImageSourceRef, maxLongEdge: Int): Image? {
        val cgImage = thumbnail(source, maxLongEdge, hdr = false) ?: return null
        try {
            return cgImage.toSkiaImage()
        } finally {
            CGImageRelease(cgImage)
        }
    }

    /** Decoded with its gain map applied, as extended-range pixels for a native HDR view; the caller releases it. */
    fun decodeHdr(source: CGImageSourceRef, maxLongEdge: Int): CGImageRef? = thumbnail(source, maxLongEdge, hdr = true)

    /** An Ultra HDR JPEG's gain map, found as every platform finds it, or an HEIC's auxiliary one, which only ImageIO reads. */
    fun hasGainmap(source: CGImageSourceRef, bytes: ByteArray): Boolean =
        UltraHdr.gainmapRange(bytes) != null || source.auxiliary(kCGImageAuxiliaryDataTypeISOGainMap) != null ||
            source.auxiliary(kCGImageAuxiliaryDataTypeHDRGainMap) != null

    private fun thumbnail(source: CGImageSourceRef, maxLongEdge: Int, hdr: Boolean): CGImageRef? {
        val options = CFDictionaryCreateMutable(null, 5, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            ?: throw IllegalStateException("Couldn't create decode options")
        val maxSize = CFBridgingRetain(NSNumber(int = maxLongEdge))
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
        CFDictionarySetValue(options, kCGImageSourceShouldCacheImmediately, kCFBooleanTrue)
        CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, maxSize)
        if (hdr) CFDictionarySetValue(options, kCGImageSourceDecodeRequest, kCGImageSourceDecodeToHDR)
        return try {
            CGImageSourceCreateThumbnailAtIndex(source, 0.convert(), options)
        } finally {
            CFRelease(maxSize)
            CFRelease(options)
        }
    }
}

// Drawing into a context of the matching colour space keeps P3 pixels P3 for Skia to carry on.
private fun CGImageRef.toSkiaImage(): Image {
    val width = CGImageGetWidth(this).toInt()
    val height = CGImageGetHeight(this).toInt()
    val wide = CGImageGetColorSpace(this)?.let { CGColorSpaceIsWideGamutRGB(it) } == true
    val opaque = CGImageGetAlphaInfo(this) in OPAQUE_ALPHA_INFO
    val info = ImageInfo(
        width,
        height,
        ColorType.RGBA_8888,
        if (opaque) ColorAlphaType.OPAQUE else ColorAlphaType.PREMUL,
        if (wide) ColorSpace.displayP3 else ColorSpace.sRGB,
    )
    val bitmap = Bitmap()
    var pixels: Pixmap? = null
    val colorSpace = CGColorSpaceCreateWithName(if (wide) kCGColorSpaceDisplayP3 else kCGColorSpaceSRGB)
    try {
        // Zeroed: the context draws source-over, so a transparent image would show whatever was there.
        check(bitmap.allocPixelsFlags(info, zeroPixels = true)) { "Couldn't allocate $width x $height pixels" }
        val target = checkNotNull(bitmap.peekPixels()).also { pixels = it }
        val context = CGBitmapContextCreate(
            interpretCPointer<ByteVar>(target.addr),
            width.convert(),
            height.convert(),
            8.convert(),
            target.rowBytes.convert(),
            colorSpace,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
        ) ?: throw IllegalStateException("Couldn't create a bitmap context")
        try {
            CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), this)
        } finally {
            CGContextRelease(context)
        }
        bitmap.setImmutable()
        return Image.makeFromBitmap(bitmap)
    } finally {
        CGColorSpaceRelease(colorSpace)
        pixels?.close()
        bitmap.close()
    }
}

private val OPAQUE_ALPHA_INFO = setOf(
    CGImageAlphaInfo.kCGImageAlphaNone,
    CGImageAlphaInfo.kCGImageAlphaNoneSkipLast,
    CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst,
)

/** [header], the file's start, holds an HEIC's ISO tone map, which some ImageIO builds (the simulator's) don't hand over. */
internal fun CGImageSourceRef.auxiliaryGainmap(header: () -> ByteArray): HdrGainmap? {
    val props = CGImageSourceCopyPropertiesAtIndex(this, 0.convert(), null)?.let { CFBridgingRelease(it) as? NSDictionary }
    val orientation = (props?.objectForKey(kCGImagePropertyOrientation.ns) as? NSNumber)?.intValue ?: 1
    val iso = auxiliary(kCGImageAuxiliaryDataTypeISOGainMap)
    if (iso != null) {
        val metadata = ImageMetadataScrubber.heifToneMap(header()) ?: iso.xmp()?.let(AppleToneMapXmp::decode)
        if (metadata != null) return iso.toGainmap(metadata, orientation, lut = null)
        Logger.w(tag = "HdrGainmap") { "An ISO gain map without readable metadata; trying Apple's" }
    }
    val apple = auxiliary(kCGImageAuxiliaryDataTypeHDRGainMap) ?: return null
    val headroom = apple.xmp()?.let(AppleHdrGainmap::headroomFromXmp) ?: props?.makerNoteHeadroom()
    if (headroom == null) {
        Logger.w(tag = "HdrGainmap") { "An Apple gain map without a headroom; the photo goes as SDR" }
        return null
    }
    return apple.toGainmap(AppleHdrGainmap.metadata(headroom), orientation, AppleHdrGainmap.isoValues(headroom))
}

private fun CGImageSourceRef.auxiliary(type: CFStringRef?): NSDictionary? =
    CGImageSourceCopyAuxiliaryDataInfoAtIndex(this, 0.convert(), type)?.let { CFBridgingRelease(it) as? NSDictionary }

private fun NSDictionary.xmp(): String? {
    val metadata = objectForKey(kCGImageAuxiliaryDataInfoMetadata.ns) ?: return null
    val ref: CGImageMetadataRef = CFBridgingRetain(metadata)?.reinterpret() ?: return null
    val data = try {
        CGImageMetadataCreateXMPData(ref, null)?.let { CFBridgingRelease(it) as? NSData }
    } finally {
        CFRelease(ref)
    }
    return data?.let { NSString.create(it, NSUTF8StringEncoding)?.toString() }
}

private fun NSDictionary.makerNoteHeadroom(): Double? {
    val apple = objectForKey(kCGImagePropertyMakerAppleDictionary.ns) as? NSDictionary ?: return null
    val maker33 = (apple.objectForKey("33") as? NSNumber)?.doubleValue ?: return null
    val maker48 = (apple.objectForKey("48") as? NSNumber)?.doubleValue ?: return null
    return AppleHdrGainmap.headroom(maker33, maker48)
}

private fun NSDictionary.toGainmap(metadata: GainmapMetadata, orientation: Int, lut: ByteArray?): HdrGainmap? {
    val data = objectForKey(kCGImageAuxiliaryDataInfoData.ns) as? NSData ?: return null
    val description = objectForKey(kCGImageAuxiliaryDataInfoDataDescription.ns) as? NSDictionary ?: return null
    fun int(key: String) = (description.objectForKey(key) as? NSNumber)?.intValue
    val format = int("PixelFormat")
    val channels = when (format?.toUInt()) {
        PIXEL_FORMAT_ONE_COMPONENT_8 -> 1
        PIXEL_FORMAT_32_BGRA -> 4
        else -> {
            Logger.w(tag = "HdrGainmap") { "A gain map in pixel format $format; the photo goes as SDR" }
            return null
        }
    }
    val height = int("Height") ?: return null
    val rowBytes = int("BytesPerRow") ?: return null
    if (height <= 0 || rowBytes <= 0 || data.length.toLong() < rowBytes.toLong() * height) return null
    val pixels = GainmapPixels(data.bytes!!.reinterpret<ByteVar>().readBytes(rowBytes * height), int("Width") ?: return null, height, rowBytes, channels)
    return pixels.toGainmap(metadata, orientation, lut)
}

private inline fun <T> withProperties(source: CGImageSourceRef, read: (CFDictionaryRef) -> T?): T? {
    val props = CGImageSourceCopyPropertiesAtIndex(source, 0.convert(), null) ?: return null
    try {
        return read(props)
    } finally {
        CFRelease(props)
    }
}

// CF lookups, not NSDictionary.objectForKey: a CFStringRef key doesn't bridge to NSString in K/N.
private fun cfInt(dict: CFDictionaryRef, key: CFStringRef?): Int? = memScoped {
    val number: CFNumberRef = CFDictionaryGetValue(dict, key)?.reinterpret() ?: return null
    val out = alloc<IntVar>()
    if (CFNumberGetValue(number, kCFNumberIntType, out.ptr)) out.value else null
}

private const val PIXEL_FORMAT_ONE_COMPONENT_8 = 0x4C303038u // 'L008'
private const val PIXEL_FORMAT_32_BGRA = 0x42475241u // 'BGRA'
