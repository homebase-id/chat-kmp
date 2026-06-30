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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import id.homebase.resources.contactbook_confirmed_empty
import id.homebase.resources.contactbook_filter_all
import id.homebase.resources.contactbook_filter_confirmed
import id.homebase.resources.contactbook_filter_introduced
import id.homebase.resources.contactbook_filter_requests
import id.homebase.resources.contactbook_filter_requests_count
import id.homebase.resources.contactbook_introduced_empty
import id.homebase.resources.contactbook_no_results
import id.homebase.resources.contactbook_request_incoming
import id.homebase.resources.contactbook_request_outgoing
import id.homebase.resources.contactbook_requests_empty
import org.jetbrains.compose.resources.stringResource

@Composable
fun ContactBookContent(
    uiState: ContactBookUiState,
    onAction: (ContactBookUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        FilterRow(uiState.filter, uiState.requests.size, onAction)

        if (uiState.filter == ContactFilter.REQUESTS) {
            RequestsList(uiState, onAction)
            return@Column
        }

        val list = when (uiState.filter) {
            ContactFilter.ALL -> uiState.contacts
            ContactFilter.INTRODUCED -> uiState.introduced
            ContactFilter.CONFIRMED -> uiState.confirmed
            ContactFilter.REQUESTS -> emptyList() // handled above
        }

        when {
            uiState.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            list.isEmpty() && uiState.searchQuery.isNotBlank() ->
                CenterText(stringResource(MR.string.contactbook_no_results))

            list.isEmpty() -> when (uiState.filter) {
                ContactFilter.INTRODUCED ->
                    CenterText(stringResource(MR.string.contactbook_introduced_empty))

                ContactFilter.CONFIRMED ->
                    CenterText(stringResource(MR.string.contactbook_confirmed_empty))

                else -> ContactBookEmptyState(
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
                                // filter (an introduced contact is still a connection).
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

/** Pending connection requests (incoming + outgoing), newest first — no A–Z sectioning. */
@Composable
private fun RequestsList(
    uiState: ContactBookUiState,
    onAction: (ContactBookUiAction) -> Unit,
) {
    when {
        uiState.isLoading && uiState.requests.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        uiState.requests.isEmpty() && uiState.searchQuery.isNotBlank() ->
            CenterText(stringResource(MR.string.contactbook_no_results))

        uiState.requests.isEmpty() ->
            CenterText(stringResource(MR.string.contactbook_requests_empty))

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            items(
                uiState.requests,
                key = { "${it.direction}_${it.entry.uniqueId}" },
            ) { request ->
                ContactBookRow(
                    entry = request.entry,
                    onClick = { onAction(ContactBookUiAction.ContactClicked(request.entry)) },
                    trailing = { RequestDirectionChip(request.direction) },
                )
            }
        }
    }
}

@Composable
private fun RequestDirectionChip(direction: RequestDirection) {
    val label = when (direction) {
        RequestDirection.INCOMING -> stringResource(MR.string.contactbook_request_incoming)
        RequestDirection.OUTGOING -> stringResource(MR.string.contactbook_request_outgoing)
    }
    val container = when (direction) {
        RequestDirection.INCOMING -> MaterialTheme.colorScheme.primaryContainer
        RequestDirection.OUTGOING -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (direction) {
        RequestDirection.INCOMING -> MaterialTheme.colorScheme.onPrimaryContainer
        RequestDirection.OUTGOING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
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
    requestCount: Int,
    onAction: (ContactBookUiAction) -> Unit,
) {
    val requestsLabel = if (requestCount > 0) {
        stringResource(MR.string.contactbook_filter_requests_count, requestCount)
    } else {
        stringResource(MR.string.contactbook_filter_requests)
    }
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
            selected = filter == ContactFilter.INTRODUCED,
            onClick = { onAction(ContactBookUiAction.FilterChanged(ContactFilter.INTRODUCED)) },
            label = { Text(stringResource(MR.string.contactbook_filter_introduced)) },
        )
        FilterChip(
            selected = filter == ContactFilter.CONFIRMED,
            onClick = { onAction(ContactBookUiAction.FilterChanged(ContactFilter.CONFIRMED)) },
            label = { Text(stringResource(MR.string.contactbook_filter_confirmed)) },
        )
        FilterChip(
            selected = filter == ContactFilter.REQUESTS,
            onClick = { onAction(ContactBookUiAction.FilterChanged(ContactFilter.REQUESTS)) },
            label = { Text(requestsLabel) },
        )
    }
}
