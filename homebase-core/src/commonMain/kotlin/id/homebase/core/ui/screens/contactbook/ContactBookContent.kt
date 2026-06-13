package id.homebase.core.ui.screens.contactbook

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.contactbook.components.ContactBookEmptyState
import id.homebase.core.ui.screens.contactbook.components.ContactBookRow
import id.homebase.resources.MR
import id.homebase.resources.contactbook_filter_all
import id.homebase.resources.contactbook_filter_homebase
import id.homebase.resources.contactbook_filter_imported
import id.homebase.resources.contactbook_no_results
import org.jetbrains.compose.resources.stringResource

@Composable
fun ContactBookContent(
    uiState: ContactBookUiState,
    onAction: (ContactBookUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        FilterRow(uiState.filter, onAction)

        when {
            uiState.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            uiState.contacts.isEmpty() && uiState.searchQuery.isBlank() &&
                uiState.filter == ContactFilter.ALL ->
                ContactBookEmptyState(
                    showImport = uiState.importSupported,
                    onAddClick = { onAction(ContactBookUiAction.AddClicked) },
                    onImportClick = { onAction(ContactBookUiAction.ImportClicked) },
                )

            uiState.contacts.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(MR.string.contactbook_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                val grouped = uiState.contacts.groupBy { it.sectionKey }
                val sections = grouped.keys.sorted()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    sections.forEach { section ->
                        val entries = grouped[section].orEmpty()
                        item(key = "h_$section") {
                            Text(
                                text = section,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        items(entries, key = { it.uniqueId.toString() }) { entry ->
                            ContactBookRow(
                                entry = entry,
                                onClick = { onAction(ContactBookUiAction.ContactClicked(entry)) },
                                connected = entry.odinId?.lowercase() in uiState.connectedOdinIds,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    filter: ContactFilter,
    onAction: (ContactBookUiAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = filter == ContactFilter.ALL,
            onClick = { onAction(ContactBookUiAction.FilterChanged(ContactFilter.ALL)) },
            label = { Text(stringResource(MR.string.contactbook_filter_all)) },
        )
        FilterChip(
            selected = filter == ContactFilter.HOMEBASE,
            onClick = { onAction(ContactBookUiAction.FilterChanged(ContactFilter.HOMEBASE)) },
            label = { Text(stringResource(MR.string.contactbook_filter_homebase)) },
        )
        FilterChip(
            selected = filter == ContactFilter.IMPORTED,
            onClick = { onAction(ContactBookUiAction.FilterChanged(ContactFilter.IMPORTED)) },
            label = { Text(stringResource(MR.string.contactbook_filter_imported)) },
        )
    }
}
