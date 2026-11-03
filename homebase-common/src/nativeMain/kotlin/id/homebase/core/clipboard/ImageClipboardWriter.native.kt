@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.clipboard

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIPasteboard

internal actual suspend fun writePngToClipboard(png: ByteArray) {
    require(png.isNotEmpty()) { "empty PNG" }
    val data = png.usePinned { NSData.create(bytes = it.addressOf(0), length = png.size.toULong()) }
    UIPasteboard.generalPasteboard.setData(data, forPasteboardType = "public.png")
}
