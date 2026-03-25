package id.homebase.core.clipboard

/**
 * Reads image data from the system clipboard.
 * Returns PNG-encoded image bytes, or null if no image is available.
 */
expect fun getImageFromClipboard(): ByteArray?
