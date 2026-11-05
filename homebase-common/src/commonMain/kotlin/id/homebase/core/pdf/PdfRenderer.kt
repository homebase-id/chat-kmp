package id.homebase.core.pdf

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Renders pages of one local PDF file. One instance holds one open document; it is not thread
 * safe, and every call blocks, so use it off the main thread and from one thread at a time.
 * Call [close] when done.
 *
 * Web has no PDF engine: [open] throws [UnsupportedOperationException] and [pageCount] is 0.
 */
expect class PdfRenderer() {
    /**
     * Opens the PDF at [filePath], closing any document already open. Throws if the file is
     * missing, unreadable, not a PDF, or needs a password the user must supply (an empty user
     * password is tried automatically). After a failed call nothing is open.
     */
    fun open(filePath: String)

    /** Number of pages of the open document; 0 when none is open or after [close]. */
    val pageCount: Int

    /**
     * Renders the zero-based page [index] on a white background, scaled to fit inside
     * [width] x [height] pixels with its aspect ratio kept, so one dimension is usually smaller.
     * Throws [IllegalArgumentException] when nothing is open and throws when [index] is out of
     * range.
     */
    fun renderPage(index: Int, width: Int, height: Int): ImageBitmap

    /** Releases the document. Safe to call when nothing is open, and more than once. */
    fun close()
}
