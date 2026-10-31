package id.homebase.core.pdf

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun PdfPageViewer(
    filePath: String,
    onTap: (() -> Unit)?,
    modifier: Modifier,
) = RenderedPdfPageViewer(filePath, onTap, modifier)
