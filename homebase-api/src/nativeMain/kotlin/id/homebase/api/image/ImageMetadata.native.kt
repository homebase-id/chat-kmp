@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.api.image

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringRef
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSArray
import platform.Foundation.firstObject
import platform.Foundation.NSDictionary
import platform.Foundation.NSNumber
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImagePropertyExifDateTimeOriginal
import platform.ImageIO.kCGImagePropertyExifFocalLenIn35mmFilm
import platform.ImageIO.kCGImagePropertyExifDigitalZoomRatio
import platform.ImageIO.kCGImagePropertyExifSceneCaptureType
import platform.ImageIO.kCGImagePropertyExifCustomRendered
import platform.ImageIO.kCGImagePropertyExifColorSpace
import platform.ImageIO.kCGImagePropertyExifSubjectDistance
import platform.ImageIO.kCGImagePropertyTIFFImageDescription
import platform.ImageIO.kCGImagePropertyTIFFArtist
import platform.ImageIO.kCGImagePropertyTIFFCopyright
import platform.ImageIO.kCGImagePropertyExifUserComment
import platform.ImageIO.kCGImagePropertyExifBodySerialNumber
import platform.ImageIO.kCGImagePropertyExifLensSerialNumber
import platform.ImageIO.kCGImagePropertyGPSDateStamp
import platform.ImageIO.kCGImagePropertyGPSTimeStamp
import platform.ImageIO.kCGImagePropertyGPSSpeed
import platform.ImageIO.kCGImagePropertyGPSSpeedRef
import platform.ImageIO.kCGImagePropertyExifLensMake
import platform.ImageIO.kCGImagePropertyExifLensModel
import platform.ImageIO.kCGImagePropertyExifExposureBiasValue
import platform.ImageIO.kCGImagePropertyExifExposureProgram
import platform.ImageIO.kCGImagePropertyExifMeteringMode
import platform.ImageIO.kCGImagePropertyExifFlash
import platform.ImageIO.kCGImagePropertyExifWhiteBalance
import platform.ImageIO.kCGImagePropertyTIFFSoftware
import platform.ImageIO.kCGImagePropertyGPSImgDirection
import platform.ImageIO.kCGImagePropertyGPSHPositioningError
import platform.ImageIO.kCGImagePropertyExifDictionary
import platform.ImageIO.kCGImagePropertyExifExposureTime
import platform.ImageIO.kCGImagePropertyExifFNumber
import platform.ImageIO.kCGImagePropertyExifFocalLength
import platform.ImageIO.kCGImagePropertyExifISOSpeedRatings
import platform.ImageIO.kCGImagePropertyExifOffsetTime
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
import platform.ImageIO.kCGImagePropertyTIFFDateTime
import platform.ImageIO.kCGImagePropertyTIFFDictionary
import platform.ImageIO.kCGImagePropertyTIFFMake
import platform.ImageIO.kCGImagePropertyTIFFModel
import platform.ImageIO.kCGImagePropertyTIFFOrientation

actual fun readImageMetadata(srcBytes: ByteArray): ImageMetadata? {
    if (srcBytes.isEmpty()) return null
    return try {
        val source = ImageIoDecode.open(srcBytes) ?: return null
        try {
            source.readMetadata()?.let { if (it.userComment == null) it else it.withJpegComment(srcBytes) }
        } finally {
            CFRelease(source)
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
            ?: tiff?.objectForKey(kCGImagePropertyTIFFDateTime.ns) as? String
    )
    val offset = parseExifUtcOffset(
        exif?.objectForKey(kCGImagePropertyExifOffsetTimeOriginal.ns) as? String
            ?: exif?.objectForKey(kCGImagePropertyExifOffsetTime.ns) as? String
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
        fNumber = exif?.positiveDouble(kCGImagePropertyExifFNumber),
        exposureTimeSeconds = exif?.positiveDouble(kCGImagePropertyExifExposureTime),
        focalLengthMm = exif?.positiveDouble(kCGImagePropertyExifFocalLength),
        iso = ((exif?.objectForKey(kCGImagePropertyExifISOSpeedRatings.ns) as? NSArray)?.firstObject as? NSNumber)
            ?.intValue?.takeIf { it > 0 },
        lensMake = exifName(exif?.objectForKey(kCGImagePropertyExifLensMake.ns) as? String),
        lensModel = exifName(exif?.objectForKey(kCGImagePropertyExifLensModel.ns) as? String),
        exposureBiasEv = exifExposureBias(exif?.double(kCGImagePropertyExifExposureBiasValue)),
        exposureProgram = exifExposureProgram(exif?.int(kCGImagePropertyExifExposureProgram)),
        meteringMode = exifMeteringMode(exif?.int(kCGImagePropertyExifMeteringMode)),
        flash = exif?.int(kCGImagePropertyExifFlash),
        whiteBalance = exifWhiteBalance(exif?.int(kCGImagePropertyExifWhiteBalance)),
        software = exifName(tiff?.objectForKey(kCGImagePropertyTIFFSoftware.ns) as? String),
        modifiedAt = parseExifLocalDateTime(tiff?.objectForKey(kCGImagePropertyTIFFDateTime.ns) as? String),
        imageDirectionDegrees = exifImageDirection(gps?.double(kCGImagePropertyGPSImgDirection)),
        horizontalErrorMeters = exifHorizontalError(gps?.double(kCGImagePropertyGPSHPositioningError)),
        focalLength35mm = exifFocalLength35mm(exif?.int(kCGImagePropertyExifFocalLenIn35mmFilm)),
        digitalZoomRatio = exifDigitalZoom(exif?.double(kCGImagePropertyExifDigitalZoomRatio)),
        sceneCaptureType = exifSceneCaptureType(exif?.int(kCGImagePropertyExifSceneCaptureType)),
        customRendered = exifCustomRendered(exif?.int(kCGImagePropertyExifCustomRendered)),
        colorSpace = exifColorSpace(exif?.int(kCGImagePropertyExifColorSpace)),
        subjectDistanceMeters = exifSubjectDistance(exif?.double(kCGImagePropertyExifSubjectDistance)),
        imageDescription = exifName(tiff?.objectForKey(kCGImagePropertyTIFFImageDescription.ns) as? String),
        artist = exifName(tiff?.objectForKey(kCGImagePropertyTIFFArtist.ns) as? String),
        copyright = exifName(tiff?.objectForKey(kCGImagePropertyTIFFCopyright.ns) as? String),
        // ImageIO hands it over decoded, its character code taken off.
        userComment = exifComment(exif?.objectForKey(kCGImagePropertyExifUserComment.ns) as? String),
        bodySerial = exifName(exif?.objectForKey(kCGImagePropertyExifBodySerialNumber.ns) as? String),
        lensSerial = exifName(exif?.objectForKey(kCGImagePropertyExifLensSerialNumber.ns) as? String),
        gpsTime = exifGpsTime(gps?.objectForKey(kCGImagePropertyGPSDateStamp.ns) as? String, gps?.objectForKey(kCGImagePropertyGPSTimeStamp.ns) as? String),
        speedKmh = exifSpeedKmh(gps?.double(kCGImagePropertyGPSSpeed), gps?.objectForKey(kCGImagePropertyGPSSpeedRef.ns) as? String),
    )
}

private fun NSDictionary.double(key: CFStringRef?): Double? = (objectForKey(key.ns) as? NSNumber)?.doubleValue

private fun NSDictionary.int(key: CFStringRef?): Int? = (objectForKey(key.ns) as? NSNumber)?.intValue

private fun NSDictionary.positiveDouble(key: CFStringRef?): Double? = double(key)?.takeIf { it.isFinite() && it > 0 }

// NSDictionary.objectForKey boxes a raw CFStringRef instead of bridging it, so every lookup would miss.
internal val CFStringRef?.ns: Any get() = CFBridgingRelease(CFRetain(this))!!

/**
 * ImageIO reads a UNICODE UserComment big-endian whatever the file's order, and keeps a JIS one the shared rule drops:
 * a JPEG's comes from the shared reader, from [bytes], all of it or its head. Asked only when ImageIO found one, on
 * both of iOS's paths, so they agree and a backup reads no more of each photo.
 */
internal fun ImageMetadata.withJpegComment(bytes: ByteArray): ImageMetadata =
    // ImageIO's own where the shared reader can't reach it, past the bytes given or behind a broken IFD.
    if (jpegExifLittleEndian(bytes) != null) copy(userComment = readJpegExifCamera(bytes)?.userComment ?: userComment.takeUnless { jpegExifCommentIsJis(bytes) }) else this
