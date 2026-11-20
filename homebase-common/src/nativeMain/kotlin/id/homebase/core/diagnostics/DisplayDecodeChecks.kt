@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.diagnostics

import coil3.BitmapImage
import coil3.Image
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import id.homebase.core.image.CG_BGRA_PREMUL_BITMAP_INFO
import id.homebase.core.image.NativeImageDecoder
import id.homebase.core.image.SkiaAnimatedImage
import id.homebase.core.image.addNativeImageDecoders
import id.homebase.core.image.encodedImageFetchResult
import id.homebase.api.foundation.toByteArray
import id.homebase.api.platform.peakFootprintGrowth
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
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
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

/** The on-screen decode of a Photos-library picture, measured in the app: a test binary can't link the app's frameworks. */
object DisplayDecodeChecks {
    /** [run] for Swift, called back on a background thread. */
    fun runChecks(onDone: (CheckReport) -> Unit) {
        launchChecks({ run() }, onDone)
    }

    // One cold measurement each: a repeat reuses the pages the allocator kept from the first, and reads as no growth.
    suspend fun run(): CheckReport = checkRun {
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
            .components {
                add(EncodedPhotoFetcher.Factory())
                addNativeImageDecoders()
            }
            .memoryCache(null)
            .diskCache(null)
            .build()
        val big = quadrants(8064, 6048)
        val photos = try {
            listOf(JPEG, HEIC, PNG).associateWith { encode(it, listOf(big)) }
        } finally {
            CGImageRelease(big)
        }
        verify("the meter sees a full-size decode") { theMeterSeesAFullSizeDecode(photos.getValue(JPEG)) }
        verify("a 48 MP photo becomes a thumbnail without a full-size bitmap") {
            for (type in listOf(JPEG, HEIC)) thumbnailWithoutAFullSizeBitmap(loader, type, photos.getValue(type))
        }
        verify("a PNG is sized too") { aPngIsSizedToo(loader, photos.getValue(PNG)) }
        verify("every EXIF orientation shows upright") { everyExifOrientationShowsUpright(loader) }
        verify("an animated GIF stays animated") { anAnimatedGifStaysAnimated(loader) }
    }

    private suspend fun CheckRun.theMeterSeesAFullSizeDecode(jpeg: NSData) {
        val bytes = jpeg.toByteArray()
        val (_, growth) = peakFootprintGrowth { NativeImageDecoder.decodeThumbnail(bytes) { w, h -> maxOf(w, h) }.also { it?.close() } }
        val growthMb = growth / MB
        measured("full-size decode of a 48 MP JPEG: peak +$growthMb MB")
        check(growthMb >= FULL_BITMAP_MB * 3 / 4) { "a full-size 48 MP decode grew memory by only $growthMb MB; the meter can't be trusted" }
    }

    private suspend fun CheckRun.thumbnailWithoutAFullSizeBitmap(loader: ImageLoader, type: String, photo: NSData) {
        val (image, growth) = peakFootprintGrowth { display(loader, photo, THUMB_PX) }
        val growthMb = growth / MB
        measured("$type 48 MP for a $THUMB_PX px thumbnail: ${image.width}x${image.height}, peak +$growthMb MB")
        check(maxOf(image.width, image.height) == THUMB_PX) { "$type thumbnail is ${image.width}x${image.height}" }
        check(growthMb < FULL_BITMAP_MB / 2) {
            "$type: a $THUMB_PX px thumbnail grew memory by $growthMb MB; a full-size bitmap is $FULL_BITMAP_MB MB"
        }
    }

    private suspend fun CheckRun.aPngIsSizedToo(loader: ImageLoader, png: NSData) {
        val (image, growth) = peakFootprintGrowth { display(loader, png, THUMB_PX) }
        // Recorded, not bounded: Coil's own Skia decoder takes a PNG and decodes it in full first.
        measured("PNG 48 MP for a $THUMB_PX px thumbnail: ${image.width}x${image.height}, peak +${growth / MB} MB")
        check(maxOf(image.width, image.height) == THUMB_PX) { "PNG thumbnail is ${image.width}x${image.height}" }
    }

    private suspend fun everyExifOrientationShowsUpright(loader: ImageLoader) {
        val source = quadrants(400, 300)
        val failures = mutableListOf<String>()
        try {
            for (type in listOf(JPEG, HEIC)) {
                for ((orientation, expected) in UPRIGHT) {
                    val image = display(loader, encode(type, listOf(source), orientation), 200)
                    val (w, h) = if (orientation >= 5) 150 to 200 else 200 to 150
                    val bitmap = (image as? BitmapImage)?.bitmap
                    if (bitmap == null || image.width != w || image.height != h) {
                        failures += "$type orientation $orientation: ${image.width}x${image.height} ${image::class.simpleName}, expected a ${w}x$h bitmap"
                        continue
                    }
                    Corner.entries.forEachIndexed { i, displayed ->
                        val x = if (displayed == Corner.TL || displayed == Corner.BL) w / 4 else w * 3 / 4
                        val y = if (displayed == Corner.TL || displayed == Corner.TR) h / 4 else h * 3 / 4
                        val shown = nearest(bitmap.getColor(x, y))
                        if (shown != expected[i]) failures += "$type orientation $orientation: $displayed shows ${shown ?: "no known colour"}, expected ${expected[i]}"
                    }
                }
            }
        } finally {
            CGImageRelease(source)
        }
        check(failures.isEmpty()) { failures.joinToString("; ") }
    }

    private suspend fun anAnimatedGifStaysAnimated(loader: ImageLoader) {
        val first = quadrants(40, 30)
        val second = quadrants(40, 30, Corner.entries.reversed())
        val gif = try {
            encode(GIF, listOf(first, second))
        } finally {
            CGImageRelease(first)
            CGImageRelease(second)
        }
        val image = display(loader, gif, 200)
        check(image is SkiaAnimatedImage) { "a two-frame GIF decoded to ${image::class.simpleName}, not the animated decoder's image" }
    }

    private suspend fun display(loader: ImageLoader, photo: NSData, px: Int): Image {
        val request = ImageRequest.Builder(PlatformContext.INSTANCE).data(EncodedPhoto(photo)).size(px).build()
        return when (val result = loader.execute(request)) {
            is SuccessResult -> result.image
            is ErrorResult -> throw IllegalStateException("decode failed: ${result.throwable}", result.throwable)
        }
    }

    private class EncodedPhoto(val data: NSData)

    private class EncodedPhotoFetcher(private val photo: EncodedPhoto, private val options: Options) : Fetcher {
        override suspend fun fetch(): FetchResult? = encodedImageFetchResult(photo.data, options.fileSystem)

        class Factory : Fetcher.Factory<EncodedPhoto> {
            override fun create(data: EncodedPhoto, options: Options, imageLoader: ImageLoader): Fetcher =
                EncodedPhotoFetcher(data, options)
        }
    }

    private enum class Corner(val r: Int, val g: Int, val b: Int) {
        TL(230, 30, 30),
        TR(30, 200, 30),
        BL(30, 30, 230),
        BR(230, 230, 30),
    }

    private fun nearest(argb: Int): Corner? {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val (corner, distance) = Corner.entries
            .map { it to (it.r - r) * (it.r - r) + (it.g - g) * (it.g - g) + (it.b - b) * (it.b - b) }
            .minBy { it.second }
        return corner.takeIf { distance < 60 * 60 * 3 }
    }

    /** Four flat quadrants, [colours] in TL, TR, BL, BR order. */
    private fun quadrants(width: Int, height: Int, colours: List<Corner> = Corner.entries): CGImageRef {
        val space = CGColorSpaceCreateDeviceRGB()
        val context = CGBitmapContextCreate(null, width.toULong(), height.toULong(), 8u, 0u, space, CG_BGRA_PREMUL_BITMAP_INFO)
        CGColorSpaceRelease(space)
        checkNotNull(context) { "no ${width}x$height bitmap context" }
        val w2 = width / 2
        val h2 = height / 2
        // CG's origin is bottom-left: the image's top half is y >= h2.
        val rects = listOf(0 to h2, w2 to h2, 0 to 0, w2 to 0)
        colours.zip(rects).forEach { (c, origin) ->
            CGContextSetRGBFillColor(context, c.r / 255.0, c.g / 255.0, c.b / 255.0, 1.0)
            CGContextFillRect(context, CGRectMake(origin.first.toDouble(), origin.second.toDouble(), w2.toDouble(), h2.toDouble()))
        }
        val image = CGBitmapContextCreateImage(context)
        CGContextRelease(context)
        return checkNotNull(image) { "no ${width}x$height image" }
    }

    private fun encode(type: String, images: List<CGImageRef>, orientation: Int = 1): NSData {
        val data = checkNotNull(CFDataCreateMutable(null, 0)) { "no CFData" }
        // Owned by Kotlin from here, so a failure below can't leak the buffer.
        val encoded = CFBridgingRelease(data) as NSData
        val uti = cfString(type)
        val destination = CGImageDestinationCreateWithData(data, uti, images.size.toULong(), null)
        CFRelease(uti)
        checkNotNull(destination) { "ImageIO can't write $type on this simulator" }
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

    private const val JPEG = "public.jpeg"
    private const val HEIC = "public.heic"
    private const val PNG = "public.png"
    private const val GIF = "com.compuserve.gif"
    private const val THUMB_PX = 480
    private const val MB = 1024L * 1024
    private const val FULL_BITMAP_MB = 8064L * 6048 * 4 / MB

    // For each EXIF orientation, the stored quadrant an upright view shows at its TL, TR, BL and BR.
    private val UPRIGHT: Map<Int, List<Corner>> = mapOf(
        1 to listOf(Corner.TL, Corner.TR, Corner.BL, Corner.BR),
        2 to listOf(Corner.TR, Corner.TL, Corner.BR, Corner.BL),
        3 to listOf(Corner.BR, Corner.BL, Corner.TR, Corner.TL),
        4 to listOf(Corner.BL, Corner.BR, Corner.TL, Corner.TR),
        5 to listOf(Corner.TL, Corner.BL, Corner.TR, Corner.BR),
        6 to listOf(Corner.BL, Corner.TL, Corner.BR, Corner.TR),
        7 to listOf(Corner.BR, Corner.TR, Corner.BL, Corner.TL),
        8 to listOf(Corner.TR, Corner.BR, Corner.TL, Corner.BL),
    )
}
