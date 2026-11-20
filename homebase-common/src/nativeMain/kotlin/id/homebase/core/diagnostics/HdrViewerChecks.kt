@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.diagnostics

import id.homebase.api.diagnostics.CheckReport
import id.homebase.api.diagnostics.ImageIOEncoder
import id.homebase.api.diagnostics.ImageIOEncoder.HEIC
import id.homebase.api.diagnostics.ImageIOEncoder.JPEG
import id.homebase.api.diagnostics.checkRun
import id.homebase.api.diagnostics.launchChecks
import id.homebase.api.foundation.toByteArray
import id.homebase.core.image.decodeHdrPhoto
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.set
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorCreate
import platform.CoreGraphics.CGColorRelease
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetFillColorWithColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreateCopyWithContentHeadroom
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder16Little
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGBitmapFloatComponents
import platform.CoreGraphics.kCGColorSpaceExtendedLinearDisplayP3
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.Foundation.NSProcessInfo
import kotlin.math.max

/** What the full-screen viewers draw in HDR on iOS, measured in the app as [DisplayDecodeChecks] is. */
object HdrViewerChecks {
    /** [run] for Swift, called back on a background thread. */
    fun runChecks(onDone: (CheckReport) -> Unit) {
        launchChecks({ run() }, onDone)
    }

    suspend fun run(): CheckReport = checkRun {
        verify("an SDR photo isn't") {
            val sdr = sdrImage(WIDTH, HEIGHT)
            val bytes = try {
                ImageIOEncoder.encode(JPEG, listOf(sdr)).toByteArray()
            } finally {
                CGImageRelease(sdr)
            }
            check(decodeHdrPhoto(bytes, SHOWN_PX) == null) { "an SDR JPEG decoded as HDR" }
        }
        // The simulator's ImageIO writes no gain maps (JPEG or HEIC fail to finalize), so these need a phone.
        if (NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null) {
            measured("photos with a gain map: skipped, the simulator can't write them")
            return@checkRun
        }
        val hdr = hdrImage(WIDTH, HEIGHT)
        val withGainmap = try {
            listOf(JPEG, HEIC).associateWith { encodeWithGainmap(it, hdr) }
        } finally {
            CGImageRelease(hdr)
        }
        verify("a photo with a gain map is shown in HDR, no larger than asked") {
            for ((type, bytes) in withGainmap) {
                val photo = checkNotNull(decodeHdrPhoto(bytes, SHOWN_PX)) { "$type with a gain map decoded without HDR" }
                // Recorded, not bounded: the simulator decodes in HDR but reports no headroom; a phone does.
                measured("$type with a gain map: ${photo.width}x${photo.height}, headroom ${photo.headroom}, HDR ${photo.image.isHighDynamicRange()}")
                check(max(photo.width, photo.height) == SHOWN_PX) { "$type decoded at ${photo.width}x${photo.height}" }
            }
        }
        verify("the decode is never larger than the photo") {
            val photo = checkNotNull(decodeHdrPhoto(withGainmap.getValue(JPEG), WIDTH * 4)) { "no HDR decode" }
            check(photo.width == WIDTH && photo.height == HEIGHT) { "decoded at ${photo.width}x${photo.height}, the photo is ${WIDTH}x$HEIGHT" }
        }
    }

    // Left half diffuse white, right half four times brighter, so the encoder has highlights to keep in a gain map.
    private fun hdrImage(width: Int, height: Int): CGImageRef {
        val space = checkNotNull(CGColorSpaceCreateWithName(kCGColorSpaceExtendedLinearDisplayP3)) { "no extended linear P3" }
        try {
            val context = checkNotNull(
                CGBitmapContextCreate(
                    null, width.toULong(), height.toULong(), 16u, 0u, space,
                    CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapFloatComponents or kCGBitmapByteOrder16Little,
                ),
            ) { "no ${width}x$height half-float context" }
            try {
                fill(context, space, 1.0, 0, width / 2, height)
                fill(context, space, HEADROOM.toDouble(), width / 2, width - width / 2, height)
                val image = checkNotNull(CGBitmapContextCreateImage(context)) { "no HDR image" }
                return try {
                    checkNotNull(CGImageCreateCopyWithContentHeadroom(HEADROOM, image)) { "no headroom copy" }
                } finally {
                    CGImageRelease(image)
                }
            } finally {
                CGContextRelease(context)
            }
        } finally {
            CGColorSpaceRelease(space)
        }
    }

    private fun sdrImage(width: Int, height: Int): CGImageRef {
        val space = checkNotNull(CGColorSpaceCreateWithName(kCGColorSpaceSRGB)) { "no sRGB" }
        try {
            val context = checkNotNull(
                CGBitmapContextCreate(
                    null, width.toULong(), height.toULong(), 8u, 0u, space,
                    CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
                ),
            ) { "no ${width}x$height context" }
            try {
                fill(context, space, 0.7, 0, width, height)
                return checkNotNull(CGBitmapContextCreateImage(context)) { "no SDR image" }
            } finally {
                CGContextRelease(context)
            }
        } finally {
            CGColorSpaceRelease(space)
        }
    }

    private fun fill(context: CGContextRef, space: CGColorSpaceRef, level: Double, x: Int, width: Int, height: Int) = memScoped {
        val components = allocArray<DoubleVar>(4)
        for (i in 0..2) components[i] = level
        components[3] = 1.0
        val color = checkNotNull(CGColorCreate(space, components)) { "no colour" }
        CGContextSetFillColorWithColor(context, color)
        CGContextFillRect(context, CGRectMake(x.toDouble(), 0.0, width.toDouble(), height.toDouble()))
        CGColorRelease(color)
    }

    private fun encodeWithGainmap(type: String, image: CGImageRef): ByteArray =
        ImageIOEncoder.encode(type, listOf(image), isoGainmap = true).toByteArray()

    private const val WIDTH = 1200
    private const val HEIGHT = 900
    private const val SHOWN_PX = 600
    private const val HEADROOM = 4f
}
