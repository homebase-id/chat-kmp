package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.homebase.core.pdf.PdfPageViewer
import id.homebase.core.util.formatTimestamp
import id.homebase.resources.MR
import id.homebase.resources.chat_message_download_file
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * Fullscreen PDF viewer for a chat attachment (issue #909). Mirrors
 * [FullScreenMediaViewer]'s chrome so PDFs feel like the image viewer: a
 * name + date top bar that a tap toggles, back-arrow to dismiss, and the
 * shared [PdfPageViewer] (whose native Android/iOS renderer provides pinch
 * zoom). [filePath] is the already-decrypted local file, produced by the
 * DecryptFile path; null while it's still decrypting → spinner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPdfViewer(
    title: String,
    userDate: Instant,
    filePath: String?,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showUI by remember { mutableStateOf(true) }

    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
    ) {
        if (filePath == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            PdfPageViewer(
                filePath = filePath,
                onTap = { showUI = !showUI },
                modifier = Modifier.fillMaxSize(),
            )
        }

        AnimatedVisibility(
            visible = showUI,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = formatTimestamp(userDate),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
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
        }
    }
}
