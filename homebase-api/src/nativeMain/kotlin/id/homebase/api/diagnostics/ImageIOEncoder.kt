@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.api.diagnostics

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ptr
import platform.CoreFoundation.CFDataCreateMutable
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGImageRef
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithData
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.kCGImagePropertyGIFDelayTime
import platform.ImageIO.kCGImagePropertyGIFDictionary
import platform.ImageIO.kCGImagePropertyOrientation

/** Test photos written by ImageIO, as an iPhone writes them, for the on-device checks and benchmarks. */
object ImageIOEncoder {
    const val JPEG = "public.jpeg"
    const val HEIC = "public.heic"
    const val PNG = "public.png"
    const val GIF = "com.compuserve.gif"

    /** [images] as one file of [type]; more than one image is a GIF's frames, 0.1 s each. [orientation] is EXIF's. */
    fun encode(type: String, images: List<CGImageRef>, orientation: Int = 1): NSData {
        val data = checkNotNull(CFDataCreateMutable(null, 0)) { "no CFData" }
        // Owned by Kotlin from here, so a failure below can't leak the buffer.
        val encoded = CFBridgingRelease(data) as NSData
        val uti = cfString(type)
        val destination = CGImageDestinationCreateWithData(data, uti, images.size.toULong(), null)
        CFRelease(uti)
        checkNotNull(destination) { "ImageIO can't write $type here" }
        val properties = cfDictionary()
        if (orientation != 1) setNumber(properties, kCGImagePropertyOrientation, NSNumber(int = orientation))
        if (type == GIF) {
            val gif = cfDictionary()
            setNumber(gif, kCGImagePropertyGIFDelayTime, NSNumber(double = 0.1))
            CFDictionarySetValue(properties, kCGImagePropertyGIFDictionary, gif)
            CFRelease(gif)
        }
        images.forEach { CGImageDestinationAddImage(destination, it, properties) }
        val written = CGImageDestinationFinalize(destination)
        CFRelease(properties)
        CFRelease(destination)
        check(written) { "ImageIO failed to write $type" }
        return encoded
    }

    private fun cfString(value: String): CFStringRef =
        checkNotNull(CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)) { "no CFString" }

    private fun cfDictionary(): CFMutableDictionaryRef =
        checkNotNull(CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)) { "no CFDictionary" }

    private fun setNumber(dictionary: CFMutableDictionaryRef, key: CFStringRef?, number: NSNumber) {
        val value = CFBridgingRetain(number)
        CFDictionarySetValue(dictionary, key, value)
        CFRelease(value)
    }
}
