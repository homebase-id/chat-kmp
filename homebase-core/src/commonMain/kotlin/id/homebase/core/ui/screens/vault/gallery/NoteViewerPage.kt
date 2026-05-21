package id.homebase.core.ui.screens.vault.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichText
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.ui.screens.vault.VaultUploaderService
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.util.applyMarkDownContent
import id.homebase.resources.MR
import id.homebase.resources.vault_note_load_failed_preview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

private sealed interface NoteViewerState {
    data object Loading : NoteViewerState
    data object Ready : NoteViewerState
    data object Error : NoteViewerState
}

@OptIn(ExperimentalRichTextApi::class)
@Composable
fun NoteViewerPage(
    file: VaultEntry,
    uploaderService: VaultUploaderService,
    fileOperationsProvider: FileOperationsProvider,
    onToggleUI: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var state by remember { mutableStateOf<NoteViewerState>(NoteViewerState.Loading) }
    val richTextState = rememberRichTextState()

    LaunchedEffect(file.fileId) {
        state = NoteViewerState.Loading
        try {
            val payloadKey = file.payloadDescriptors.firstOrNull()?.key
                ?: VaultEntry.DEFAULT_PAYLOAD_KEY
            val tempPath = withContext(Dispatchers.Default) {
                uploaderService.downloadPayload(file, payloadKey)
            }
            if (tempPath == null) {
                state = NoteViewerState.Error
                return@LaunchedEffect
            }
            val bytes = withContext(Dispatchers.Default) {
                fileOperationsProvider.readFileBytes(tempPath)
            }
            try { fileOperationsProvider.deleteTempFile(tempPath) } catch (_: Exception) {}
            richTextState.applyMarkDownContent(bytes.decodeToString())
            state = NoteViewerState.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            state = NoteViewerState.Error
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onToggleUI() }) },
    ) {
        when (state) {
            is NoteViewerState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            is NoteViewerState.Ready -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(horizontal = 20.dp, vertical = 24.dp),
                    ) {
                        BasicRichText(
                            state = richTextState,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = Color(0xFF1B1B1D),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            is NoteViewerState.Error -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(MR.string.vault_note_load_failed_preview),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
