@file:OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)

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
import id.homebase.api.platform.processFootprintBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import platform.posix.memcpy
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/**
 * What the app does with a Photos-library picture on screen: its encoded bytes, as PHAssetFetcher hands them over,
 * through the app's own decoder chain. The photos are drawn here with ImageIO, so every expected size, colour and
 * number is known. Runs in the app (iosAppTests in CI, the dev menu on a phone): a test binary can't link the app's
 * frameworks.
 */
object DisplayDecodeChecks {
    /** [run] for Swift, called back on a background thread. */
    fun runChecks(onDone: (CheckReport) -> Unit) {
        CoroutineScope(Dispatchers.Default).launch { onDone(run()) }
    }

    private val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
        .components {
            add(EncodedPhotoFetcher.Factory())
            addNativeImageDecoders()
        }
        .memoryCache(null)
        .diskCache(null)
        .build()

    suspend fun run(): CheckReport = withContext(Dispatchers.Default) {
        val failures = mutableListOf<String>()
        val measurements = mutableListOf<String>()
        suspend fun verify(name: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures += "$name: ${e.message ?: e}"
            }
        }
        verify("the meter sees a full-size decode") { theMeterSeesAFullSizeDecode(measurements) }
        verify("a 48 MP photo becomes a thumbnail without a full-size bitmap") { fortyEightMegapixelThumbnails(measurements) }
        verify("a PNG is sized too") { aPngIsSizedToo(measurements) }
        verify("every EXIF orientation shows upright") { everyExifOrientationShowsUpright() }
        verify("an animated GIF stays animated") { anAnimatedGifStaysAnimated() }
        CheckReport(failures, measurements)
    }

    private suspend fun theMeterSeesAFullSizeDecode(measurements: MutableList<String>) {
        val photo = bytesOf(fortyEightMegapixel(JPEG))
        val (image, growthMb) = peakGrowthMb { NativeImageDecoder.decodeThumbnail(photo) { w, h -> maxOf(w, h) } }
        image?.close()
        measurements += "full-size decode of a 48 MP JPEG: peak +$growthMb MB"
        check(growthMb >= FULL_BITMAP_MB * 3 / 4) { "a full-size 48 MP decode grew memory by only $growthMb MB; the meter can't be trusted" }
    }

    private suspend fun fortyEightMegapixelThumbnails(measurements: MutableList<String>) {
        for (type in listOf(JPEG, HEIC)) {
            val photo = fortyEightMegapixel(type)
            val (image, growthMb) = peakGrowthMb { display(photo, THUMB_PX) }
            measurements += "$type 48 MP for a $THUMB_PX px thumbnail: ${image.width}x${image.height}, peak +$growthMb MB"
            check(maxOf(image.width, image.height) == THUMB_PX) { "$type thumbnail is ${image.width}x${image.height}" }
            check(growthMb < FULL_BITMAP_MB / 2) {
                "$type: a $THUMB_PX px thumbnail grew memory by $growthMb MB; a full-size bitmap is $FULL_BITMAP_MB MB"
            }
        }
    }

    private suspend fun aPngIsSizedToo(measurements: MutableList<String>) {
        val photo = fortyEightMegapixel(PNG)
        val (image, growthMb) = peakGrowthMb { display(photo, THUMB_PX) }
        // Recorded, not bounded: Coil's own Skia decoder takes a PNG and may decode it in full first.
        measurements += "PNG 48 MP for a $THUMB_PX px thumbnail: ${image.width}x${image.height}, peak +$growthMb MB"
        check(maxOf(image.width, image.height) == THUMB_PX) { "PNG thumbnail is ${image.width}x${image.height}" }
    }

    private suspend fun everyExifOrientationShowsUpright() {
        val source = quadrants(400, 300)
        val failures = mutableListOf<String>()
        for (type in listOf(JPEG, HEIC)) {
            for ((orientation, expected) in UPRIGHT) {
                val image = display(encode(type, listOf(source), orientation), 200)
                val (w, h) = if (orientation >= 5) 150 to 200 else 200 to 150
                if (image.width != w || image.height != h) {
                    failures += "$type orientation $orientation: ${image.width}x${image.height}, expected ${w}x$h"
                    continue
                }
                val bitmap = checkNotNull(image as? BitmapImage) { "$type orientation $orientation: not a bitmap" }.bitmap
                Corner.entries.forEachIndexed { i, displayed ->
                    val x = if (displayed == Corner.TL || displayed == Corner.BL) w / 4 else w * 3 / 4
                    val y = if (displayed == Corner.TL || displayed == Corner.TR) h / 4 else h * 3 / 4
                    val shown = nearest(bitmap.getColor(x, y))
                    if (shown != expected[i]) failures += "$type orientation $orientation: $displayed shows ${shown ?: "no known colour"}, expected ${expected[i]}"
                }
            }
        }
        CGImageRelease(source)
        check(failures.isEmpty()) { failures.joinToString("; ") }
    }

    private suspend fun anAnimatedGifStaysAnimated() {
        val first = quadrants(40, 30)
        val second = quadrants(40, 30, Corner.entries.reversed())
        val image = display(encode(GIF, listOf(first, second)), 200)
        CGImageRelease(first)
        CGImageRelease(second)
        check(image is SkiaAnimatedImage) { "a two-frame GIF decoded to ${image::class.simpleName}, not the animated decoder's image" }
    }

    private suspend fun display(photo: NSData, px: Int): Image {
        val request = ImageRequest.Builder(PlatformContext.INSTANCE).data(EncodedPhoto(photo)).size(px).build()
        return when (val result = loader.execute(request)) {
            is SuccessResult -> result.image
            is ErrorResult -> throw IllegalStateException("decode failed: ${result.throwable}", result.throwable)
        }
    }

    private suspend fun <T> peakGrowthMb(block: suspend () -> T): Pair<T, Long> = coroutineScope {
        GC.collect()
        val base = processFootprintBytes()
        var peak = base
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                peak = maxOf(peak, processFootprintBytes())
                delay(1)
            }
        }
        val value = try { block() } finally { sampler.cancelAndJoin() }
        peak = maxOf(peak, processFootprintBytes())
        value to (peak - base) / MB
    }

    private fun fortyEightMegapixel(type: String): NSData {
        val image = quadrants(8064, 6048)
        try {
            return encode(type, listOf(image))
        } finally {
            CGImageRelease(image)
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
        return CFBridgingRelease(data) as NSData
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

    private fun bytesOf(data: NSData): ByteArray {
        val bytes = ByteArray(data.length.toInt())
        bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return bytes
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
