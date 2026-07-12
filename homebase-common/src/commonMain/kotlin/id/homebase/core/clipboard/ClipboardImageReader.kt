package id.homebase.core.clipboard

/**
 * Reads image data from the system clipboard.
 * Returns PNG-encoded image bytes, or null if no image is available.
 */
expect fun getImageFromClipboard(): ByteArray?

/**
 * Reads an image off the clipboard on demand (menu/tap-driven). suspend so web can use the
 * async Clipboard API; other platforms run synchronously inside. Returns original bytes or null.
 */
expect suspend fun readClipboardImage(): ByteArray?
