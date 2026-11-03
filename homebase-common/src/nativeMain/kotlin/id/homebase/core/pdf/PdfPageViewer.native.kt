@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.pdf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
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
    onFailed: () -> Unit,
    modifier: Modifier,
) {
    val document = remember(filePath) {
        runCatching { PDFDocument(NSURL.fileURLWithPath(filePath)) }.getOrNull()?.takeIf { !it.isLocked() && it.pageCount() > 0uL }
    }
    if (document == null) {
        val currentOnFailed by rememberUpdatedState(onFailed)
        LaunchedEffect(filePath) { currentOnFailed() }
        return
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
