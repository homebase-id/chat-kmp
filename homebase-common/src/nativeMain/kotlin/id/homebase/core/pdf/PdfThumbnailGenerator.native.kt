@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.pdf

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawPDFPage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGDataProviderCreateWithData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGPDFDocumentCreateWithProvider
import platform.CoreGraphics.CGPDFDocumentGetNumberOfPages
import platform.CoreGraphics.CGPDFDocumentGetPage
import platform.CoreGraphics.CGPDFDocumentRelease
import platform.CoreGraphics.CGPDFPageGetBoxRect
import platform.CoreGraphics.kCGPDFMediaBox
import platform.Foundation.NSData
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

private val BITMAP_INFO: UInt = 1u or 8192u

actual fun generatePdfThumbnail(bytes: ByteArray, maxWidth: Int): PdfThumbnailResult? {
    return try {
        bytes.usePinned { pinned ->
            val provider = CGDataProviderCreateWithData(
                info = null,
                data = pinned.addressOf(0),
                size = bytes.size.toULong(),
                releaseData = null,
            ) ?: return null

            val document = CGPDFDocumentCreateWithProvider(provider)
            CGDataProviderRelease(provider)
            document ?: return null

            try {
                val pageCount = CGPDFDocumentGetNumberOfPages(document).toInt()
                val page = CGPDFDocumentGetPage(document, 1u) ?: return null
                val mediaBox = CGPDFPageGetBoxRect(page, kCGPDFMediaBox)
                val pageWidth = mediaBox.useContents { size.width }
                val scale = maxWidth.toDouble() / pageWidth
                val bitmapWidth = (pageWidth * scale).toInt()
                val bitmapHeight = (mediaBox.useContents { size.height } * scale).toInt()

                val colorSpace = CGColorSpaceCreateDeviceRGB()
                try {
                    val bytesPerRow = bitmapWidth * 4
                    val bitmapData = ByteArray(bytesPerRow * bitmapHeight)

                    val result = bitmapData.usePinned { bmpPinned ->
                        val context = CGBitmapContextCreate(
                            data = bmpPinned.addressOf(0),
                            width = bitmapWidth.toULong(),
                            height = bitmapHeight.toULong(),
                            bitsPerComponent = 8u,
                            bytesPerRow = bytesPerRow.toULong(),
                            space = colorSpace,
                            bitmapInfo = BITMAP_INFO,
                        ) ?: return null

                        CGContextScaleCTM(context, scale, scale)
                        CGContextDrawPDFPage(context, page)

                        val cgImage = CGBitmapContextCreateImage(context)
                        CGContextRelease(context)
                        cgImage ?: return null

                        val uiImage = UIImage(cGImage = cgImage)
                        CGImageRelease(cgImage)

                        val jpegData = UIImageJPEGRepresentation(uiImage, 0.8)
                            ?: return null

                        PdfThumbnailResult(
                            thumbnailBytes = jpegData.toKotlinByteArray(),
                            pageCount = pageCount,
                        )
                    }
                    result
                } finally {
                    CGColorSpaceRelease(colorSpace)
                }
            } finally {
                CGPDFDocumentRelease(document)
            }
        }
    } catch (_: Exception) {
        null
    }
}

@OptIn(BetaInteropApi::class)
private fun NSData.toKotlinByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) return ByteArray(0)
    val result = ByteArray(length)
    result.usePinned { pinned ->
        @Suppress("UNCHECKED_CAST")
        platform.posix.memcpy(pinned.addressOf(0), this.bytes, this.length)
    }
    return result
}
