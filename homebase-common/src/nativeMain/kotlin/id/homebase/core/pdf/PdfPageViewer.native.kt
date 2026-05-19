@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.pdf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFView
import platform.UIKit.UIView

@Composable
actual fun PdfPageViewer(
    filePath: String,
    onTap: (() -> Unit)?,
    modifier: Modifier,
) {
    val document = remember(filePath) {
        PDFDocument(NSURL.fileURLWithPath(filePath))
    }

    UIKitView<UIView>(
        factory = {
            PDFView().apply {
                setAutoScales(true)
                setDocument(document)
            }
        },
        update = { view ->
            val pdfView = view as PDFView
            if (pdfView.document() !== document) {
                pdfView.setDocument(document)
            }
        },
        modifier = modifier,
    )
}
