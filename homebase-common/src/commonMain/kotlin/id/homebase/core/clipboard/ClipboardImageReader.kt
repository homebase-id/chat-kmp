package id.homebase.core.clipboard

/**
 * Reads image data from the system clipboard.
 * Returns PNG-encoded image bytes, or null if no image is available.
 */
expect fun getImageFromClipboard(): ByteArray?

class ClipboardContents(val image: ByteArray?, val text: String?)

/**
 * Reads the clipboard's image (original bytes) and plain text for a menu Paste, in one read so web
 * asks the browser once. suspend for web's async Clipboard API; other platforms read synchronously.
 */
expect suspend fun readClipboard(): ClipboardContents

/**
 * Whether the clipboard may hold an image, checked without reading it (so iOS shows no paste
 * alert and Android no paste toast). False only when the platform can tell there is none; web
 * cannot tell without a permissioned read, so it is always true there.
 */
expect fun clipboardMayHoldImage(): Boolean

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
