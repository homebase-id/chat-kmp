@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.clipboard

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIPasteboard

actual val canCopyImageToClipboard: Boolean = true

internal actual suspend fun writePngToClipboard(png: suspend () -> ByteArray) {
    val png = png()
    require(png.isNotEmpty()) { "empty PNG" }
    val data = png.usePinned { NSData.create(bytes = it.addressOf(0), length = png.size.toULong()) }
    UIPasteboard.generalPasteboard.setData(data, forPasteboardType = "public.png")
}
