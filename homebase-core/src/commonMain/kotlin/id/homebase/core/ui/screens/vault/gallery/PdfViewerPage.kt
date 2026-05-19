package id.homebase.core.ui.screens.vault.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import id.homebase.api.file.FileOperationsProvider
import id.homebase.chat.services.LocalAttachmentContext
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.pdf.PdfPageViewer
import id.homebase.core.ui.screens.vault.VaultUploaderService
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.resources.MR
import id.homebase.resources.vault_pdf_preview_error
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private sealed interface PdfViewerState {
    data object Loading : PdfViewerState
    data class Ready(val tempFilePath: String) : PdfViewerState
    data object Error : PdfViewerState
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
        val prev = state
        if (prev is PdfViewerState.Ready) {
            try { fileOperationsProvider.deleteTempFile(prev.tempFilePath) } catch (_: Exception) { }
        }
        state = PdfViewerState.Loading

        try {
            val payloadKey = file.payloadDescriptors.firstOrNull()?.key ?: "vlt_pg_00"
            val tempPath = withContext(Dispatchers.Default) {
                uploaderService.downloadPayload(file, payloadKey)
            }
            state = if (tempPath != null) PdfViewerState.Ready(tempPath) else PdfViewerState.Error
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            state = PdfViewerState.Error
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val current = state
            if (current is PdfViewerState.Ready) {
                try { fileOperationsProvider.deleteTempFile(current.tempFilePath) } catch (_: Exception) { }
            }
        }
    }

    var revealViewer by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state is PdfViewerState.Ready) {
            kotlinx.coroutines.delay(600)
            revealViewer = true
        } else {
            revealViewer = false
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is PdfViewerState.Loading -> {
                PdfLoadingPreview(file = file)
            }

            is PdfViewerState.Ready -> {
                PdfPageViewer(
                    filePath = s.tempFilePath,
                    onTap = onToggleUI,
                    modifier = Modifier.fillMaxSize(),
                )
                AnimatedVisibility(
                    visible = !revealViewer,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    PdfLoadingPreview(file = file)
                }
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

@Composable
private fun PdfLoadingPreview(file: VaultEntry) {
    val store = koinInject<LocalAttachmentContextStore>()
    val payloadKey = file.payloadDescriptors.firstOrNull()?.key ?: "vlt_pg_00"
    val localCtx by store.observe(file.uniqueId, payloadKey)
        .collectAsStateWithLifecycle(initialValue = store.get(file.uniqueId, payloadKey))
    val thumbPath = (localCtx as? LocalAttachmentContext.Image)?.localFilePath

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (thumbPath != null) {
            AsyncImage(
                model = thumbPath,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.inversePrimary,
        )
    }
}
