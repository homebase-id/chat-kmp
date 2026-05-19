package id.homebase.core.ui.screens.vault.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.ui.screens.vault.VaultUploaderService
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.resources.MR
import id.homebase.resources.vault_text_preview_error
import id.homebase.resources.vault_text_truncated
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

private const val MAX_TEXT_BYTES = 1_048_576

private sealed interface TextViewerState {
    data object Loading : TextViewerState
    data class Ready(val content: String, val truncated: Boolean) : TextViewerState
    data object Error : TextViewerState
}

@Composable
fun TextViewerPage(
    file: VaultEntry,
    uploaderService: VaultUploaderService,
    fileOperationsProvider: FileOperationsProvider,
    onToggleUI: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var state by remember { mutableStateOf<TextViewerState>(TextViewerState.Loading) }

    LaunchedEffect(file.fileId) {
        state = TextViewerState.Loading
        try {
            val payloadKey = file.payloadDescriptors.firstOrNull()?.key ?: "vlt_pg_00"
            val tempPath = withContext(Dispatchers.Default) {
                uploaderService.downloadPayload(file, payloadKey)
            }
            if (tempPath == null) {
                state = TextViewerState.Error
            } else {
                val fileSize = withContext(Dispatchers.Default) {
                    fileOperationsProvider.getFileSize(tempPath)
                }
                val truncated = fileSize > MAX_TEXT_BYTES
                val bytes = withContext(Dispatchers.Default) {
                    fileOperationsProvider.readFileHeaderBytes(tempPath, MAX_TEXT_BYTES)
                }
                state = TextViewerState.Ready(bytes.decodeToString(), truncated)
                try { fileOperationsProvider.deleteTempFile(tempPath) } catch (_: Exception) { }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            state = TextViewerState.Error
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onToggleUI() }) },
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            is TextViewerState.Loading -> {
                CircularProgressIndicator()
            }

            is TextViewerState.Ready -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (s.truncated) {
                        Text(
                            text = stringResource(MR.string.vault_text_truncated),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(
                            text = s.content,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(16.dp),
                        )
                    }
                }
            }

            is TextViewerState.Error -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(MR.string.vault_text_preview_error),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
