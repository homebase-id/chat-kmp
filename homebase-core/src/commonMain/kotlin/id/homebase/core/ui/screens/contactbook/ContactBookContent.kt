package id.homebase.core.ui.screens.contactbook

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import id.homebase.resources.contactbook_circle_unvetted
import id.homebase.resources.contactbook_filter_all
import id.homebase.resources.contactbook_no_results
import id.homebase.resources.contactbook_requests_header
import id.homebase.resources.contactbook_unvetted_empty
import org.jetbrains.compose.resources.stringResource

@Composable
fun ContactBookContent(
    uiState: ContactBookUiState,
    onAction: (ContactBookUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        FilterRow(uiState.filter, onAction)

        // Incoming requests are the actionable set (outgoing has nothing to do here but Cancel,
        // already reachable from the resolved identity itself) — surfaced as a normal section at
        // the top of the list, not a separate pill, so it reads like part of the list rather than
        // a toast that bounces you elsewhere.
        val incomingRequests = uiState.requests.filter { it.direction == RequestDirection.INCOMING }

        val list = when (uiState.filter) {
            ContactFilter.ALL -> uiState.contacts
            ContactFilter.UNVETTED -> uiState.unvetted
        }

        when {
            uiState.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            list.isEmpty() && incomingRequests.isEmpty() && uiState.searchQuery.isNotBlank() ->
                CenterText(stringResource(MR.string.contactbook_no_results))

            list.isEmpty() && incomingRequests.isEmpty() -> when (uiState.filter) {
                ContactFilter.UNVETTED ->
                    CenterText(stringResource(MR.string.contactbook_unvetted_empty))

                ContactFilter.ALL -> ContactBookEmptyState(
                    onAddClick = { onAction(ContactBookUiAction.AddClicked) },
                )
            }

            else -> {
                val grouped = list.groupBy { it.sectionKey }
                val sections = grouped.keys.sorted()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    if (incomingRequests.isNotEmpty()) {
                        item(key = "h_requests") {
                            Text(
                                text = stringResource(MR.string.contactbook_requests_header),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        items(incomingRequests, key = { "req_${it.entry.uniqueId}" }) { request ->
                            ContactBookRow(
                                entry = request.entry,
                                onClick = { onAction(ContactBookUiAction.ContactClicked(request.entry)) },
                            )
                        }
                    }
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
                                // Check shows whenever the identity is connected, in every
                                // filter (an unvetted contact is still a connection).
                                connected = entry.odinId?.lowercase() in uiState.connectedOdinIds,
                                introducedBy = entry.odinId?.lowercase()
                                    ?.let { uiState.introducedByDomain[it] },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterText(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = filter == ContactFilter.ALL,
            onClick = { onAction(ContactBookUiAction.FilterChanged(ContactFilter.ALL)) },
            label = { Text(stringResource(MR.string.contactbook_filter_all)) },
        )
        FilterChip(
            selected = filter == ContactFilter.UNVETTED,
            onClick = { onAction(ContactBookUiAction.FilterChanged(ContactFilter.UNVETTED)) },
            label = { Text(stringResource(MR.string.contactbook_circle_unvetted)) },
        )
    }
}
