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
import platform.CoreGraphics.CGContextDrawPDFPage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGPDFDocumentCreateWithURL
import platform.CoreGraphics.CGPDFDocumentGetNumberOfPages
import platform.CoreGraphics.CGPDFDocumentGetPage
import platform.CoreGraphics.CGPDFDocumentRelease
import platform.CoreGraphics.CGPDFDocumentRef
import platform.CoreGraphics.CGPDFPageGetBoxRect
import platform.CoreGraphics.kCGPDFMediaBox
import platform.Foundation.NSURL

private val BITMAP_INFO: UInt = 1u or 8192u

actual class PdfRenderer actual constructor() {

    private var document: CGPDFDocumentRef? = null

    actual fun open(filePath: String) {
        val url = platform.Foundation.CFBridgingRetain(NSURL.fileURLWithPath(filePath))
        try {
            document = CGPDFDocumentCreateWithURL(url as platform.CoreFoundation.CFURLRef)
                ?: throw IllegalArgumentException("Cannot open PDF: $filePath")
        } finally {
            platform.CoreFoundation.CFRelease(url)
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

            CGContextScaleCTM(context, scale, scale)
            CGContextDrawPDFPage(context, page)
            CGContextRelease(context)
        }

        val skiaImage = org.jetbrains.skia.Image.makeRaster(
            imageInfo = ImageInfo(bitmapWidth, bitmapHeight, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
            bytes = bitmapData,
            rowBytes = bytesPerRow,
        )
        return skiaImage.toComposeImageBitmap()
    }

    actual fun close() {
        document?.let { CGPDFDocumentRelease(it) }
        document = null
    }
}
