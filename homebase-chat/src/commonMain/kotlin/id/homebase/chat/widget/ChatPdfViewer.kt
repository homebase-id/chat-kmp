package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.core.pdf.PdfPageViewer
import id.homebase.resources.MR
import id.homebase.resources.chat_message_download_file
import id.homebase.resources.chat_pdf_viewer_close
import org.jetbrains.compose.resources.stringResource

/**
 * Fullscreen PDF viewer for a chat attachment (issue #909). Thin wrapper around the
 * shared [PdfPageViewer]: [filePath] is the already-decrypted local file (null while
 * the DecryptFile path is still running → spinner). Download reuses the normal
 * DownloadMedia action so the viewer also satisfies "save the PDF".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPdfViewer(
    title: String,
    filePath: String?,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(MR.string.chat_pdf_viewer_close),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onDownload, enabled = !isDownloading) {
                        if (isDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = stringResource(MR.string.chat_message_download_file),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            if (filePath == null) {
                CircularProgressIndicator()
            } else {
                PdfPageViewer(filePath = filePath, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
