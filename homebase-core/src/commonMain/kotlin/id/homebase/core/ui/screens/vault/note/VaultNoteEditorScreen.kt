package id.homebase.core.ui.screens.vault.note

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import id.homebase.chat.widget.RichTextEditorButtons
import id.homebase.core.util.applyMarkDownContent
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.vault_note_body_placeholder
import id.homebase.resources.vault_note_load_failed
import id.homebase.resources.vault_note_loading
import id.homebase.resources.vault_note_save
import id.homebase.resources.vault_note_save_failed
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

    val saveFailedMessage = stringResource(MR.string.vault_note_save_failed)
    val loadFailedMessage = stringResource(MR.string.vault_note_load_failed)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                VaultNoteEditorEvent.SaveSuccess -> onBackClick()
                VaultNoteEditorEvent.SaveFailed -> snackbarHostState.showSnackbar(saveFailedMessage)
                VaultNoteEditorEvent.LoadFailed -> {
                    snackbarHostState.showSnackbar(loadFailedMessage)
                    onBackClick()
                }
            }
        }
    }

    var hasSeeded by remember { mutableStateOf(false) }
    val loadedMarkdown = uiState.loadedMarkdown
    LaunchedEffect(loadedMarkdown) {
        if (loadedMarkdown != null && !hasSeeded) {
            richTextState.applyMarkDownContent(loadedMarkdown)
            hasSeeded = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {},
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
                                .padding(end = 16.dp)
                                .size(20.dp),
                        )
                    } else {
                        TextButton(
                            onClick = { viewModel.onSave(richTextState.toMarkdown()) },
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
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
        ) {
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

            AnimatedVisibility(
                visible = !uiState.isLoading,
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
        }
    }
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
