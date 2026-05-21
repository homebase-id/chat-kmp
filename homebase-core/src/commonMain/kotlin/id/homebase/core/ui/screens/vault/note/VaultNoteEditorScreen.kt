package id.homebase.core.ui.screens.vault.note

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import id.homebase.chat.widget.RichTextEditorButtons
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.vault_note_body_placeholder
import id.homebase.resources.vault_note_editor_title_edit
import id.homebase.resources.vault_note_editor_title_new
import id.homebase.resources.vault_note_loading
import id.homebase.resources.vault_note_save
import id.homebase.resources.vault_note_title_placeholder
import id.homebase.resources.vault_note_title_required
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultNoteEditorScreen(
    viewModel: VaultNoteEditorViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val richTextState = rememberRichTextState()
    val snackbarHostState = remember { SnackbarHostState() }

    val saveFailed = stringResource(MR.string.vault_note_save) // reuse pattern — provide error strings
    val loadFailed = stringResource(MR.string.vault_note_loading)

    // Handle one-time events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                VaultNoteEditorEvent.SaveSuccess -> onBackClick()
                VaultNoteEditorEvent.SaveFailed -> snackbarHostState.showSnackbar(saveFailed)
                VaultNoteEditorEvent.LoadFailed -> {
                    snackbarHostState.showSnackbar(loadFailed)
                    onBackClick()
                }
            }
        }
    }

    // Once loading finishes in edit mode, seed the rich text editor with the downloaded markdown
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading && !uiState.isCreateMode) {
            richTextState.setMarkdown(viewModel.getLoadedMarkdown())
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (uiState.isCreateMode) {
                            stringResource(MR.string.vault_note_editor_title_new)
                        } else {
                            stringResource(MR.string.vault_note_editor_title_edit)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
                actions = {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(20.dp),
                        )
                    } else {
                        IconButton(
                            onClick = { viewModel.onSave(richTextState.toMarkdown()) },
                            enabled = uiState.canSave,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(MR.string.vault_note_save),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .imePadding(),
        ) {
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            OutlinedTextField(
                value = uiState.title,
                onValueChange = { viewModel.onTitleChanged(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(MR.string.vault_note_title_placeholder)) },
                singleLine = true,
                isError = uiState.titleError,
                supportingText = if (uiState.titleError) {
                    { Text(stringResource(MR.string.vault_note_title_required)) }
                } else null,
            )

            RichTextEditorButtons(
                modifier = Modifier.fillMaxWidth(),
                state = richTextState,
                enabled = !uiState.isLoading,
            )

            RichTextEditor(
                state = richTextState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text(stringResource(MR.string.vault_note_body_placeholder)) },
                shape = RoundedCornerShape(0.dp),
                colors = RichTextEditorDefaults.richTextEditorColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
        }
    }
}
