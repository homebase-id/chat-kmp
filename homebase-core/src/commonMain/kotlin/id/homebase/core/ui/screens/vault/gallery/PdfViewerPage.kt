package id.homebase.core.ui.screens.vault.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.pdf.PdfPageViewer
import id.homebase.core.pdf.PdfRenderer
import id.homebase.core.ui.screens.vault.VaultUploaderService
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.resources.MR
import id.homebase.resources.vault_pdf_preview_error
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

private sealed interface PdfViewerState {
    data object Loading : PdfViewerState
    data class Ready(val renderer: PdfRenderer, val tempFilePath: String) : PdfViewerState
    data object Error : PdfViewerState
}

private fun cleanupPdfResources(
    renderer: PdfRenderer?,
    tempPath: String?,
    fileOps: FileOperationsProvider,
) {
    renderer?.close()
    tempPath?.let { try { fileOps.deleteTempFile(it) } catch (_: Exception) { } }
}

@Composable
fun PdfViewerPage(
    file: VaultEntry,
    uploaderService: VaultUploaderService,
    fileOperationsProvider: FileOperationsProvider,
    onToggleUI: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var state by remember { mutableStateOf<PdfViewerState>(PdfViewerState.Loading) }

    LaunchedEffect(file.fileId) {
        // Clean up previous Ready state when fileId changes
        val prev = state
        if (prev is PdfViewerState.Ready) {
            cleanupPdfResources(prev.renderer, prev.tempFilePath, fileOperationsProvider)
        }
        state = PdfViewerState.Loading

        var tempPath: String? = null
        var renderer: PdfRenderer? = null
        try {
            val payloadKey = file.payloadDescriptors.firstOrNull()?.key ?: "vlt_pg_00"
            tempPath = withContext(Dispatchers.Default) {
                uploaderService.downloadPayload(file, payloadKey)
            }
            if (tempPath == null) {
                state = PdfViewerState.Error
            } else {
                renderer = PdfRenderer()
                withContext(Dispatchers.Default) { renderer.open(tempPath) }
                state = PdfViewerState.Ready(renderer, tempPath)
            }
        } catch (e: CancellationException) {
            cleanupPdfResources(renderer, tempPath, fileOperationsProvider)
            throw e
        } catch (_: Exception) {
            cleanupPdfResources(renderer, tempPath, fileOperationsProvider)
            state = PdfViewerState.Error
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val current = state
            if (current is PdfViewerState.Ready) {
                cleanupPdfResources(current.renderer, current.tempFilePath, fileOperationsProvider)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is PdfViewerState.Loading -> {
                CircularProgressIndicator()
            }

            is PdfViewerState.Ready -> {
                PdfPageViewer(
                    renderer = s.renderer,
                    onTap = onToggleUI,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            is PdfViewerState.Error -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(MR.string.vault_pdf_preview_error),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
