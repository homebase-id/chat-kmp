@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.api.image

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringRef
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSNumber
import platform.Foundation.dataWithBytes
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImagePropertyExifDateTimeOriginal
import platform.ImageIO.kCGImagePropertyExifDictionary
import platform.ImageIO.kCGImagePropertyExifOffsetTimeOriginal
import platform.ImageIO.kCGImagePropertyExifPixelXDimension
import platform.ImageIO.kCGImagePropertyExifPixelYDimension
import platform.ImageIO.kCGImagePropertyGPSAltitude
import platform.ImageIO.kCGImagePropertyGPSAltitudeRef
import platform.ImageIO.kCGImagePropertyGPSDictionary
import platform.ImageIO.kCGImagePropertyGPSLatitude
import platform.ImageIO.kCGImagePropertyGPSLatitudeRef
import platform.ImageIO.kCGImagePropertyGPSLongitude
import platform.ImageIO.kCGImagePropertyGPSLongitudeRef
import platform.ImageIO.kCGImagePropertyOrientation
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.ImageIO.kCGImagePropertyTIFFDictionary
import platform.ImageIO.kCGImagePropertyTIFFMake
import platform.ImageIO.kCGImagePropertyTIFFModel
import platform.ImageIO.kCGImagePropertyTIFFOrientation

actual fun readImageMetadata(srcBytes: ByteArray): ImageMetadata? {
    if (srcBytes.isEmpty()) return null
    return try {
        val nsData = srcBytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), srcBytes.size.toULong())
        }
        @Suppress("UNCHECKED_CAST")
        val cfData = CFBridgingRetain(nsData) as CFDataRef? ?: return null
        try {
            val source = CGImageSourceCreateWithData(cfData, null) ?: return null
            try {
                source.readMetadata()
            } finally {
                CFRelease(source)
            }
        } finally {
            CFRelease(cfData)
        }
    } catch (e: Throwable) {
        Logger.w(throwable = e, tag = "readImageMetadata") { "iOS EXIF read failed" }
        null
    }
}

internal fun CGImageSourceRef.readMetadata(): ImageMetadata? = try {
    CGImageSourceCopyPropertiesAtIndex(this, 0.convert(), null)
        ?.let { CFBridgingRelease(it) as? NSDictionary }
        ?.let(::buildMetadata)
} catch (e: Throwable) {
    Logger.w(throwable = e, tag = "readImageMetadata") { "iOS EXIF read failed" }
    null
}

private fun buildMetadata(props: NSDictionary): ImageMetadata {
    val exif = props.objectForKey(kCGImagePropertyExifDictionary.ns) as? NSDictionary
    val tiff = props.objectForKey(kCGImagePropertyTIFFDictionary.ns) as? NSDictionary
    val gps = props.objectForKey(kCGImagePropertyGPSDictionary.ns) as? NSDictionary

    val capturedAt = parseExifLocalDateTime(
        exif?.objectForKey(kCGImagePropertyExifDateTimeOriginal.ns) as? String
    )
    val offset = parseExifUtcOffset(
        exif?.objectForKey(kCGImagePropertyExifOffsetTimeOriginal.ns) as? String
    )

    val latRaw = (gps?.objectForKey(kCGImagePropertyGPSLatitude.ns) as? NSNumber)?.doubleValue
    val latRef = gps?.objectForKey(kCGImagePropertyGPSLatitudeRef.ns) as? String
    val lat = latRaw?.let { if (latRef.equals("S", ignoreCase = true)) -it else it }

    val lonRaw = (gps?.objectForKey(kCGImagePropertyGPSLongitude.ns) as? NSNumber)?.doubleValue
    val lonRef = gps?.objectForKey(kCGImagePropertyGPSLongitudeRef.ns) as? String
    val lon = lonRaw?.let { if (lonRef.equals("W", ignoreCase = true)) -it else it }

    val altRaw = (gps?.objectForKey(kCGImagePropertyGPSAltitude.ns) as? NSNumber)?.doubleValue
    val altRef = (gps?.objectForKey(kCGImagePropertyGPSAltitudeRef.ns) as? NSNumber)?.intValue
    val altitude = altRaw?.let { if (altRef == 1) -it else it }

    val orientation = (
        (props.objectForKey(kCGImagePropertyOrientation.ns) as? NSNumber)?.intValue
            ?: (tiff?.objectForKey(kCGImagePropertyTIFFOrientation.ns) as? NSNumber)?.intValue
        )?.takeIf { it in 1..8 }

    val pixelWidth = (
        (props.objectForKey(kCGImagePropertyPixelWidth.ns) as? NSNumber)?.intValue
            ?: (exif?.objectForKey(kCGImagePropertyExifPixelXDimension.ns) as? NSNumber)?.intValue
        )?.takeIf { it > 0 }
    val pixelHeight = (
        (props.objectForKey(kCGImagePropertyPixelHeight.ns) as? NSNumber)?.intValue
            ?: (exif?.objectForKey(kCGImagePropertyExifPixelYDimension.ns) as? NSNumber)?.intValue
        )?.takeIf { it > 0 }

    return ImageMetadata(
        capturedAt = capturedAt,
        captureUtcOffset = offset,
        latitude = lat,
        longitude = lon,
        altitudeMeters = altitude,
        orientation = orientation,
        pixelWidth = pixelWidth,
        pixelHeight = pixelHeight,
        cameraMake = (tiff?.objectForKey(kCGImagePropertyTIFFMake.ns) as? String)
            ?.trim()?.takeIf { it.isNotEmpty() },
        cameraModel = (tiff?.objectForKey(kCGImagePropertyTIFFModel.ns) as? String)
            ?.trim()?.takeIf { it.isNotEmpty() },
    )
}

// NSDictionary.objectForKey boxes a raw CFStringRef instead of bridging it, so every lookup would miss.
private val CFStringRef?.ns: Any get() = CFBridgingRelease(CFRetain(this))!!
