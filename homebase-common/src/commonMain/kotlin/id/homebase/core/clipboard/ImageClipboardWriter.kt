package id.homebase.core.clipboard

import id.homebase.api.image.ImageFormat
import id.homebase.api.image.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Replaces the system clipboard with the image [load] returns (bytes plus content type, any format
 * the platform decodes) as a single PNG, so it pastes into other apps. Animated images are
 * flattened to their first frame. A copy replaces the previous one: two copies in a row never paste
 * the older image. Throws when the platform refuses. Call it straight from the user's gesture,
 * before any other suspension: [load] runs inside the write so the browser still counts the click.
 */
suspend fun copyImageToClipboard(load: suspend () -> Pair<ByteArray, String>) {
    writePngToClipboard {
        val (bytes, contentType) = load()
        withContext(Dispatchers.Default) { toClipboardPng(bytes, contentType) }
    }
}

internal fun toClipboardPng(bytes: ByteArray, contentType: String): ByteArray =
    if (contentType.equals("image/png", ignoreCase = true)) bytes
    else ImageUtils.compressOnly(bytes, ImageFormat.PNG, 100).bytes

/**
 * Whether [copyImageToClipboard] can work here: always on Android, iOS and desktop; on the web only
 * when the browser has the async Clipboard API with `ClipboardItem`. Hosts hide the copy action
 * when false rather than offer one that fails.
 */
expect val canCopyImageToClipboard: Boolean

/**
 * Replaces the system clipboard with the PNG [png] returns. Throws when the platform refuses the
 * write. [png] is invoked exactly once; the web passes its promise to the browser unresolved.
 */
internal expect suspend fun writePngToClipboard(png: suspend () -> ByteArray)
