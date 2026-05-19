package id.homebase.core.pdf

import androidx.compose.ui.graphics.ImageBitmap

actual class PdfRenderer actual constructor() {

    actual fun open(filePath: String) {
        throw UnsupportedOperationException("PDF rendering not supported on web")
    }

    actual val pageCount: Int get() = 0

    actual fun renderPage(index: Int, width: Int, height: Int): ImageBitmap {
        throw UnsupportedOperationException("PDF rendering not supported on web")
    }

    actual fun close() { }
}
