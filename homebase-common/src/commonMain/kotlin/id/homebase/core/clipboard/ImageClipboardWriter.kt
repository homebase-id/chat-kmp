package id.homebase.core.clipboard

import id.homebase.api.image.ImageFormat
import id.homebase.api.image.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Replaces the system clipboard with the image in [bytes] (any format the platform decodes) as a PNG,
 * so it pastes into other apps. Animated images are flattened to their first frame. Throws on failure.
 */
suspend fun copyImageToClipboard(bytes: ByteArray, contentType: String) {
    writePngToClipboard(withContext(Dispatchers.Default) { toClipboardPng(bytes, contentType) })
}

internal fun toClipboardPng(bytes: ByteArray, contentType: String): ByteArray =
    if (contentType.equals("image/png", ignoreCase = true)) bytes
    else ImageUtils.compressOnly(bytes, ImageFormat.PNG, 100).bytes

/** Replaces the system clipboard with [png]. Throws when the platform refuses the write. */
internal expect suspend fun writePngToClipboard(png: ByteArray)
