@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.pdf

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawPDFPage
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGPDFDocumentCreateWithURL
import platform.CoreGraphics.CGPDFDocumentGetNumberOfPages
import platform.CoreGraphics.CGPDFDocumentGetPage
import platform.CoreGraphics.CGPDFDocumentRelease
import platform.CoreGraphics.CGPDFDocumentRef
import platform.CoreGraphics.CGPDFPageGetBoxRect
import platform.CoreGraphics.kCGPDFMediaBox
import platform.Foundation.NSURL

// kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little → BGRA in memory
private val BITMAP_INFO: UInt = 2u or 8192u

actual class PdfRenderer actual constructor() {

    private var document: CGPDFDocumentRef? = null

    private var accessedUrl: NSURL? = null

    actual fun open(filePath: String) {
        close()
        val nsUrl = NSURL.fileURLWithPath(filePath)
        val accessed = nsUrl.startAccessingSecurityScopedResource()
        if (accessed) accessedUrl = nsUrl

        val cfUrl = platform.Foundation.CFBridgingRetain(nsUrl)
        try {
            document = CGPDFDocumentCreateWithURL(cfUrl as platform.CoreFoundation.CFURLRef)
                ?: throw IllegalArgumentException("Cannot open PDF: $filePath")
        } finally {
            platform.CoreFoundation.CFRelease(cfUrl)
        }
    }

    actual val pageCount: Int
        get() = document?.let { CGPDFDocumentGetNumberOfPages(it).toInt() } ?: 0

    actual fun renderPage(index: Int, width: Int, height: Int): ImageBitmap {
        val doc = requireNotNull(document) { "PdfRenderer not opened. Call open() first." }
        val page = CGPDFDocumentGetPage(doc, (index + 1).toULong())
            ?: throw IllegalArgumentException("Page $index not found")

        val mediaBox = CGPDFPageGetBoxRect(page, kCGPDFMediaBox)
        val (pageWidth, pageHeight) = mediaBox.useContents { size.width to size.height }
        val scaleX = width.toDouble() / pageWidth
        val scaleY = height.toDouble() / pageHeight
        val scale = minOf(scaleX, scaleY)
        val bitmapWidth = (pageWidth * scale).toInt()
        val bitmapHeight = (pageHeight * scale).toInt()

        val colorSpace = CGColorSpaceCreateDeviceRGB()
        val bytesPerRow = bitmapWidth * 4
        val bitmapData = ByteArray(bytesPerRow * bitmapHeight)

        try {
            bitmapData.usePinned { pinned ->
                val context = CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = bitmapWidth.toULong(),
                    height = bitmapHeight.toULong(),
                    bitsPerComponent = 8u,
                    bytesPerRow = bytesPerRow.toULong(),
                    space = colorSpace,
                    bitmapInfo = BITMAP_INFO,
                ) ?: throw RuntimeException("Cannot create CGContext")

                CGContextSetRGBFillColor(context, 1.0, 1.0, 1.0, 1.0)
                CGContextFillRect(context, CGRectMake(0.0, 0.0, bitmapWidth.toDouble(), bitmapHeight.toDouble()))
                CGContextScaleCTM(context, scale, scale)
                CGContextDrawPDFPage(context, page)
                CGContextRelease(context)
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }

        val skiaImage = org.jetbrains.skia.Image.makeRaster(
            imageInfo = ImageInfo(bitmapWidth, bitmapHeight, ColorType.BGRA_8888, ColorAlphaType.PREMUL),
            bytes = bitmapData,
            rowBytes = bytesPerRow,
        )
        return skiaImage.toComposeImageBitmap()
    }

    actual fun close() {
        document?.let { CGPDFDocumentRelease(it) }
        document = null
        accessedUrl?.stopAccessingSecurityScopedResource()
        accessedUrl = null
    }
}
