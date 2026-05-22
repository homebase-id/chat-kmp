package id.homebase.core.ui.screens.vault.note

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichText
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import id.homebase.chat.widget.RichTextEditorButtons
import id.homebase.core.util.applyMarkDownContent
import id.homebase.resources.MR
import id.homebase.resources.vault_delete_confirm_action
import id.homebase.resources.vault_note_body_placeholder
import id.homebase.resources.vault_note_close
import id.homebase.resources.vault_note_delete
import id.homebase.resources.vault_note_discard_action
import id.homebase.resources.vault_note_discard_message
import id.homebase.resources.vault_note_discard_title
import id.homebase.resources.vault_note_delete_confirm_message
import id.homebase.resources.vault_note_delete_confirm_title
import id.homebase.resources.vault_note_delete_failed
import id.homebase.resources.vault_note_edit
import id.homebase.resources.vault_note_load_failed
import id.homebase.resources.vault_note_load_failed_preview
import id.homebase.resources.vault_note_loading
import id.homebase.resources.vault_note_save
import id.homebase.resources.vault_note_save_failed
import id.homebase.resources.vault_note_share
import id.homebase.resources.vault_note_title_placeholder
import id.homebase.resources.vault_note_title_required
import id.homebase.resources.vault_permission_cancel
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalRichTextApi::class, ExperimentalComposeUiApi::class)
@Composable
fun VaultNoteEditorScreen(
    viewModel: VaultNoteEditorViewModel,
    onBackClick: () -> Unit,
    onShareFile: (filePath: String) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val richTextState = rememberRichTextState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var seedGeneration by remember { mutableStateOf(0) }

    val saveFailedMessage = stringResource(MR.string.vault_note_save_failed)
    val loadFailedMessage = stringResource(MR.string.vault_note_load_failed)
    val deleteFailedMessage = stringResource(MR.string.vault_note_delete_failed)

    val hasUnsavedChanges: () -> Boolean = {
        if (uiState.isCreateMode) {
            uiState.title.isNotBlank() || richTextState.toMarkdown().isNotBlank()
        } else {
            richTextState.toMarkdown() != (uiState.loadedMarkdown ?: "")
        }
    }

    val handleEditClose: () -> Unit = {
        if (hasUnsavedChanges()) {
            showDiscardConfirm = true
        } else {
            if (uiState.isCreateMode) onBackClick()
            else viewModel.onToggleEdit()
        }
    }

    val discardAndReturn: () -> Unit = {
        showDiscardConfirm = false
        if (uiState.isCreateMode) {
            onBackClick()
        } else {
            seedGeneration++
            viewModel.onToggleEdit()
        }
    }

    BackHandler(enabled = uiState.isEditing) {
        handleEditClose()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                VaultNoteEditorEvent.SaveSuccess -> {
                    if (uiState.isCreateMode) onBackClick()
                }
                VaultNoteEditorEvent.SaveFailed -> snackbarHostState.showSnackbar(saveFailedMessage)
                VaultNoteEditorEvent.LoadFailed -> {
                    snackbarHostState.showSnackbar(loadFailedMessage)
                    onBackClick()
                }
                VaultNoteEditorEvent.DeleteSuccess -> onBackClick()
                VaultNoteEditorEvent.DeleteFailed -> snackbarHostState.showSnackbar(deleteFailedMessage)
                is VaultNoteEditorEvent.ShareReady -> onShareFile(event.filePath)
            }
        }
    }

    var hasSeeded by remember { mutableStateOf(false) }
    val loadedMarkdown = uiState.loadedMarkdown
    LaunchedEffect(loadedMarkdown, seedGeneration) {
        if (loadedMarkdown != null && (!hasSeeded || seedGeneration > 0)) {
            richTextState.applyMarkDownContent(loadedMarkdown)
            hasSeeded = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (uiState.isEditing) {
                EditModeTopBar(
                    uiState = uiState,
                    onClose = handleEditClose,
                    onSave = { viewModel.onSave(richTextState.toMarkdown()) },
                )
            } else {
                ReadModeTopBar(
                    title = uiState.title,
                    showActions = !uiState.isLoading && uiState.loadedMarkdown != null,
                    onClose = onBackClick,
                    onShare = { viewModel.onShare() },
                    onEdit = { viewModel.onToggleEdit() },
                    onDelete = { showDeleteConfirm = true },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
        ) {
            // Loading
            AnimatedVisibility(
                visible = uiState.isLoading,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(MR.string.vault_note_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Edit mode
            AnimatedVisibility(
                visible = !uiState.isLoading && uiState.isEditing,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                ) {
                    NoteTitle(
                        title = uiState.title,
                        onTitleChanged = viewModel::onTitleChanged,
                        hasError = uiState.titleError,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                    )

                    RichTextEditor(
                        state = richTextState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        placeholder = {
                            Text(
                                text = stringResource(MR.string.vault_note_body_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        colors = RichTextEditorDefaults.richTextEditorColors(
                            containerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                    )

                    RichTextEditorButtons(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        state = richTextState,
                        enabled = true,
                    )
                }
            }

            // Read mode
            AnimatedVisibility(
                visible = !uiState.isLoading && !uiState.isEditing && uiState.loadedMarkdown != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    BasicRichText(
                        state = richTextState,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Error state (load failed but didn't navigate back)
            AnimatedVisibility(
                visible = !uiState.isLoading && !uiState.isEditing && uiState.loadedMarkdown == null && !uiState.isCreateMode,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
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

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(MR.string.vault_note_delete_confirm_title)) },
            text = { Text(stringResource(MR.string.vault_note_delete_confirm_message, uiState.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.onDelete()
                    },
                ) {
                    Text(
                        text = stringResource(MR.string.vault_delete_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(MR.string.vault_permission_cancel))
                }
            },
        )
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(MR.string.vault_note_discard_title)) },
            text = { Text(stringResource(MR.string.vault_note_discard_message)) },
            confirmButton = {
                TextButton(onClick = discardAndReturn) {
                    Text(
                        text = stringResource(MR.string.vault_note_discard_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(MR.string.vault_permission_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadModeTopBar(
    title: String,
    showActions: Boolean,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(MR.string.vault_note_close),
                )
            }
        },
        actions = {
            if (showActions) {
                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringResource(MR.string.vault_note_share),
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(MR.string.vault_note_edit),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(MR.string.vault_note_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditModeTopBar(
    uiState: VaultNoteEditorUiState,
    onClose: () -> Unit,
    onSave: () -> Unit,
) {
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(MR.string.vault_note_close),
                )
            }
        },
        actions = {
            if (uiState.isSaving) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(20.dp),
                )
            } else {
                TextButton(
                    onClick = onSave,
                    enabled = uiState.canSave,
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Text(
                        text = stringResource(MR.string.vault_note_save),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
        ),
    )
}

@Composable
private fun NoteTitle(
    title: String,
    onTitleChanged: (String) -> Unit,
    hasError: Boolean,
    modifier: Modifier = Modifier,
) {
    val errorColor = MaterialTheme.colorScheme.error

    Column(modifier = modifier) {
        TextField(
            value = title,
            onValueChange = onTitleChanged,
            textStyle = MaterialTheme.typography.headlineMedium,
            placeholder = {
                Text(
                    text = stringResource(MR.string.vault_note_title_placeholder),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (hasError) {
                        errorColor.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                )
            },
            singleLine = true,
            isError = hasError,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
                errorCursorColor = errorColor,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedVisibility(visible = hasError) {
            Text(
                text = stringResource(MR.string.vault_note_title_required),
                style = MaterialTheme.typography.bodySmall,
                color = errorColor,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}
