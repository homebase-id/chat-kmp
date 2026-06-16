package id.homebase.core.ui.screens.contactbook.deviceimport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.homebase.core.contactbook.DeviceContact
import id.homebase.core.ui.screens.contactbook.ContactBookError
import id.homebase.core.ui.screens.contactbook.components.AdaptiveSheet
import id.homebase.core.ui.screens.contactbook.ContactBookUiAction
import id.homebase.core.ui.screens.contactbook.ImportUiState
import id.homebase.resources.MR
import id.homebase.resources.contactbook_import_confirm
import id.homebase.resources.contactbook_import_done
import id.homebase.resources.contactbook_import_empty
import id.homebase.resources.contactbook_import_failed_permission
import id.homebase.resources.contactbook_import_failed_read
import id.homebase.resources.contactbook_import_reading
import id.homebase.resources.contactbook_import_requesting
import id.homebase.resources.contactbook_import_result
import id.homebase.resources.contactbook_import_saving
import id.homebase.resources.contactbook_import_select_all
import id.homebase.resources.contactbook_import_title
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactImportSheet(
    state: ImportUiState,
    onAction: (ContactBookUiAction) -> Unit,
) {
    AdaptiveSheet(onDismiss = { onAction(ContactBookUiAction.ImportDismiss) }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(MR.string.contactbook_import_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            when (state) {
                ImportUiState.RequestingPermission ->
                    Centered { ProgressWithLabel(stringResource(MR.string.contactbook_import_requesting)) }

                ImportUiState.Reading ->
                    Centered { ProgressWithLabel(stringResource(MR.string.contactbook_import_reading)) }

                is ImportUiState.Review -> ReviewBody(state, onAction)

                is ImportUiState.Saving -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(
                            progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(MR.string.contactbook_import_saving, state.done, state.total),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                is ImportUiState.Complete -> Column {
                    Text(
                        text = stringResource(
                            MR.string.contactbook_import_result, state.imported, state.skipped
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { onAction(ContactBookUiAction.ImportDismiss) },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(stringResource(MR.string.contactbook_import_done)) }
                }

                is ImportUiState.Failed -> Column {
                    Text(
                        text = stringResource(
                            when (state.reason) {
                                ContactBookError.PermissionDenied ->
                                    MR.string.contactbook_import_failed_permission
                                else -> MR.string.contactbook_import_failed_read
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { onAction(ContactBookUiAction.ImportDismiss) },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(stringResource(MR.string.contactbook_import_done)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewBody(
    state: ImportUiState.Review,
    onAction: (ContactBookUiAction) -> Unit,
) {
    if (state.contacts.isEmpty()) {
        Text(
            text = stringResource(MR.string.contactbook_import_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onAction(ContactBookUiAction.ImportDismiss) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(MR.string.contactbook_import_done)) }
        return
    }

    val allSelected = state.selected.size == state.contacts.size
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(MR.string.contactbook_import_select_all))
        Checkbox(
            checked = allSelected,
            onCheckedChange = { onAction(ContactBookUiAction.ImportSelectAll(it)) },
        )
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
        itemsIndexed(state.contacts) { index, contact ->
            ImportRow(
                contact = contact,
                checked = index in state.selected,
                onToggle = { onAction(ContactBookUiAction.ImportToggle(index)) },
            )
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        TextButton(onClick = { onAction(ContactBookUiAction.ImportDismiss) }) {
            Text(stringResource(MR.string.contactbook_import_done))
        }
        Button(
            onClick = { onAction(ContactBookUiAction.ImportConfirm) },
            enabled = state.selected.isNotEmpty(),
        ) {
            Text(stringResource(MR.string.contactbook_import_confirm, state.selected.size))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportRow(
    contact: DeviceContact,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    ListItem(
        leadingContent = { Checkbox(checked = checked, onCheckedChange = { onToggle() }) },
        headlineContent = { Text(contact.displayName) },
        supportingContent = {
            val sub = contact.phone ?: contact.email
            if (sub != null) Text(sub)
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(160.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ProgressWithLabel(label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}
