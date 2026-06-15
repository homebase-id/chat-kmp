package id.homebase.core.ui.screens.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import id.homebase.chat.widget.ChatMarkdown
import id.homebase.core.util.applyMarkDownContent
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.delete
import id.homebase.resources.edit
import id.homebase.resources.list_action_delete_list
import id.homebase.resources.list_action_rename
import id.homebase.resources.list_delete_body
import id.homebase.resources.list_delete_title
import id.homebase.resources.list_detail_add_action
import id.homebase.resources.list_detail_add_hint
import id.homebase.resources.list_detail_empty_body
import id.homebase.resources.list_detail_empty_title
import id.homebase.resources.list_item_edit_title
import id.homebase.resources.list_item_more
import id.homebase.resources.list_overview_more
import id.homebase.resources.menu_back
import id.homebase.resources.save
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDetailScreen(
    viewModel: ListDetailViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var listMenuOpen by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var deleteListDialog by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<ListDetailItem?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is ListDetailEvent.ListDeleted -> onNavigateBack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.string.menu_back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { listMenuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(MR.string.list_overview_more))
                        }
                        DropdownMenu(expanded = listMenuOpen, onDismissRequest = { listMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.string.list_action_rename)) },
                                onClick = { listMenuOpen = false; renameDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.string.list_action_delete_list)) },
                                onClick = { listMenuOpen = false; deleteListDialog = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            AddItemBar(
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    viewModel.addItem(draft)
                    draft = ""
                },
            )
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            uiState.items.isEmpty() -> DetailEmptyState(Modifier.fillMaxSize().padding(innerPadding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(uiState.items, key = { it.itemId.toString() }) { item ->
                    ListItemRow(
                        item = item,
                        onToggle = { viewModel.setChecked(item.itemId, !item.checked) },
                        onEdit = { editTarget = item },
                        onDelete = { viewModel.deleteItem(item.itemId) },
                    )
                }
            }
        }
    }

    if (renameDialog) {
        ListDetailTitleDialog(
            initialValue = uiState.title,
            onConfirm = { viewModel.renameList(it); renameDialog = false },
            onDismiss = { renameDialog = false },
        )
    }
    if (deleteListDialog) {
        AlertDialog(
            onDismissRequest = { deleteListDialog = false },
            title = { Text(stringResource(MR.string.list_delete_title)) },
            text = { Text(stringResource(MR.string.list_delete_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteList(); deleteListDialog = false }) {
                    Text(stringResource(MR.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteListDialog = false }) { Text(stringResource(MR.string.cancel)) }
            },
        )
    }

    editTarget?.let { target ->
        EditItemSheet(
            initialBody = target.body,
            onSave = { newBody -> viewModel.editItem(target.itemId, newBody); editTarget = null },
            onDismiss = { editTarget = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListItemRow(
    item: ListDetailItem,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = item.checked,
            onCheckedChange = { onToggle() },
        )
        Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            ChatMarkdown(
                content = item.body,
                color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                ),
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(MR.string.list_item_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(MR.string.edit)) },
                    onClick = { menuOpen = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(MR.string.delete)) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddItemBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val canSend = draft.isNotBlank()
    Surface(tonalElevation = 2.dp) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text(stringResource(MR.string.list_detail_add_hint)) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onSend, enabled = canSend) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(MR.string.list_detail_add_action))
                }
            }
        }
    }
}

@Composable
private fun ListDetailTitleDialog(
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.string.list_action_rename)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(MR.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditItemSheet(
    initialBody: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val richTextState = rememberRichTextState()
    // Seed the editor ONCE with the existing markdown body (LaunchedEffect(Unit) so it does not
    // re-apply on recomposition and clobber the user's edits).
    LaunchedEffect(Unit) { richTextState.applyMarkDownContent(initialBody) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(MR.string.list_item_edit_title), style = MaterialTheme.typography.titleMedium)
            RichTextEditor(
                state = richTextState,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
                TextButton(
                    onClick = { onSave(richTextState.toMarkdown()) },
                    enabled = richTextState.annotatedString.text.isNotBlank(),
                ) { Text(stringResource(MR.string.save)) }
            }
        }
    }
}

@Composable
private fun DetailEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ListAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Text(
            text = stringResource(MR.string.list_detail_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(MR.string.list_detail_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
