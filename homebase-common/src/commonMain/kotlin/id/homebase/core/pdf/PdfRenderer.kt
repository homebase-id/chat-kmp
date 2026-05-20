package id.homebase.core.pdf

import androidx.compose.ui.graphics.ImageBitmap

expect class PdfRenderer() {
    fun open(filePath: String)
    val pageCount: Int
    fun renderPage(index: Int, width: Int, height: Int): ImageBitmap
    fun close()
}
