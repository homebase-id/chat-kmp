package id.homebase.core.util

/**
 * A file saved while its bytes are still being made, so none is ever held whole: [write] takes them in order,
 * [close] hands the finished file to the user, and [abort] leaves nothing behind.
 */
interface StreamedSave {
    suspend fun write(bytes: ByteArray)
    suspend fun close()
    suspend fun abort()
}

/**
 * The browser's save for a file of any size: its save picker where it has one (Chrome, Edge); otherwise the site's
 * private storage on disk, downloaded from there once complete; otherwise, as a last resort, memory. Null when the
 * user cancels the picker, and on every other platform, where an app writes to its cache directory and saves that.
 * Call it while the click that asked for it is still recent: a browser shows its picker only then.
 */
expect suspend fun openBrowserSave(suggestedName: String, mimeType: String): StreamedSave?
