@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package id.homebase.core.pdf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFView
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.objc.sel_registerName

private class TapHandler(private val onTap: () -> Unit) : NSObject() {
    @Suppress("unused")
    @kotlinx.cinterop.ObjCAction
    fun handleTap() {
        onTap()
    }
}

@Composable
actual fun PdfPageViewer(
    filePath: String,
    onTap: (() -> Unit)?,
    modifier: Modifier,
) {
    val document = remember(filePath) {
        PDFDocument(NSURL.fileURLWithPath(filePath))
    }
    val tapHandler = remember {
        if (onTap != null) TapHandler(onTap) else null
    }

    UIKitView<UIView>(
        factory = {
            PDFView().apply {
                setAutoScales(true)
                setDocument(document)
                if (tapHandler != null) {
                    addGestureRecognizer(
                        UITapGestureRecognizer(
                            target = tapHandler,
                            action = sel_registerName("handleTap"),
                        ),
                    )
                }
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
