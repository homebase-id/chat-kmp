package id.homebase.core.ui.screens.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.create
import id.homebase.resources.delete
import id.homebase.resources.list_action_delete_list
import id.homebase.resources.list_action_rename
import id.homebase.resources.list_delete_body
import id.homebase.resources.list_delete_title
import id.homebase.resources.list_overview_create_title
import id.homebase.resources.list_overview_more
import id.homebase.resources.list_overview_name_hint
import id.homebase.resources.list_overview_new
import id.homebase.resources.list_overview_progress
import id.homebase.resources.list_overview_rename_title
import id.homebase.resources.lists_empty_body
import id.homebase.resources.lists_empty_title
import id.homebase.resources.lists_label
import id.homebase.resources.save
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    viewModel: ListOverviewViewModel,
    onOpenList: (Uuid) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ListOverviewRow?>(null) }
    var deleteTarget by remember { mutableStateOf<ListOverviewRow?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is ListOverviewEvent.OpenList -> onOpenList(event.listId)
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(MR.string.lists_label)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(MR.string.list_overview_new))
            }
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            uiState.rows.isEmpty() -> ListsEmptyState(Modifier.fillMaxSize().padding(innerPadding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(uiState.rows, key = { it.listId.toString() }) { row ->
                    ListOverviewRowItem(
                        row = row,
                        onClick = { onOpenList(row.listId) },
                        onRename = { renameTarget = row },
                        onDelete = { deleteTarget = row },
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        ListTitleDialog(
            titleText = stringResource(MR.string.list_overview_create_title),
            confirmText = stringResource(MR.string.create),
            initialValue = "",
            onConfirm = { viewModel.createList(it); showCreateDialog = false },
            onDismiss = { showCreateDialog = false },
        )
    }
    renameTarget?.let { target ->
        ListTitleDialog(
            titleText = stringResource(MR.string.list_overview_rename_title),
            confirmText = stringResource(MR.string.save),
            initialValue = target.title,
            onConfirm = { viewModel.renameList(target.listId, it); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(MR.string.list_delete_title)) },
            text = { Text(stringResource(MR.string.list_delete_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteList(target.listId); deleteTarget = null }) {
                    Text(stringResource(MR.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(MR.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListOverviewRowItem(
    row: ListOverviewRow,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(row.title) },
        supportingContent = {
            Text(stringResource(MR.string.list_overview_progress, row.checkedCount, row.totalCount))
        },
        leadingContent = {
            Icon(Icons.AutoMirrored.Outlined.ListAlt, contentDescription = null)
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(MR.string.list_overview_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.string.list_action_rename)) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.string.list_action_delete_list)) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
private fun ListTitleDialog(
    titleText: String,
    confirmText: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleText) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(MR.string.list_overview_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
        },
    )
}

@Composable
private fun ListsEmptyState(modifier: Modifier = Modifier) {
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
            text = stringResource(MR.string.lists_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(MR.string.lists_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
