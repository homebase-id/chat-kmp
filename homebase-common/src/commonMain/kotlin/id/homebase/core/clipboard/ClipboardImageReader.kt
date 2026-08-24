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

// Pure mime-selection core, stdlib-typed so it's unit-testable without Robolectric/mockito:
// takes the clip's mime types and a lazy byte reader instead of ClipData/ContentResolver.
// readBytes must never throw across this boundary (e.g. a stale/non-granted content:// clipboard
// URI on Android can throw FileNotFoundException/SecurityException/IOException), so the call is
// guarded here — the contract is "null or bytes", never an exception.
internal fun selectImageBytes(mimeTypes: List<String>, readBytes: () -> ByteArray?): ByteArray? {
    if (mimeTypes.none { it.startsWith("image/") }) return null
    val bytes = try { readBytes() } catch (_: Exception) { null }
    return bytes?.takeIf { it.isNotEmpty() }
}
