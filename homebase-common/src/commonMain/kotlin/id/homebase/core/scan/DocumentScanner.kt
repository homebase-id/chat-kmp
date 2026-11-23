package id.homebase.core.scan

import androidx.compose.runtime.Composable

/**
 * The platform's own document scanner: live edge detection, perspective crop and page filters,
 * multi-page. Every platform must behave the same:
 *
 *  - [launch] opens the scanner and returns at once; the result arrives through the callback
 *    given to [rememberDocumentScanner]. [onNotStarted] is called if the scanner could not be opened.
 *  - The callback gets one JPEG per page, in scan order, already cropped and upright. Never an
 *    empty list: cancel, failure and a scan with no pages call nothing. Nothing throws.
 */
fun interface DocumentScanner {
    fun launch(onNotStarted: () -> Unit)
}

/** Null where the platform has no scanner (desktop, web, Android without Google Play services); callers hide the option. */
@Composable
expect fun rememberDocumentScanner(onPages: (List<ByteArray>) -> Unit): DocumentScanner?
