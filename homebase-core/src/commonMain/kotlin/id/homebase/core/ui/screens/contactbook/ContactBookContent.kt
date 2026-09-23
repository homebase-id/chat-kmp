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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.contactbook.components.ContactBookEmptyState
import id.homebase.core.ui.screens.contactbook.components.ContactBookRow
import id.homebase.resources.MR
import id.homebase.resources.contactbook_filter_all
import id.homebase.resources.contactbook_no_results
import id.homebase.resources.contactbook_requests_header
import id.homebase.resources.contactbook_new_empty
import id.homebase.resources.contactbook_filter_circles
import id.homebase.resources.contactbook_circles_filter_empty
import id.homebase.resources.contactbook_filter_blocked
import id.homebase.resources.contactbook_blocked_filter_empty
import id.homebase.resources.contactbook_circle_unvetted
import id.homebase.resources.contactbook_unvetted_empty
import id.homebase.resources.contactbook_vetted
import id.homebase.resources.contactbook_vetted_empty
import id.homebase.core.ui.screens.contactbook.components.ContactStateIcon
import id.homebase.resources.contact_review_action
import org.jetbrains.compose.resources.stringResource

@Composable
fun ContactBookContent(
    uiState: ContactBookUiState,
    onAction: (ContactBookUiAction) -> Unit,
    modifier: Modifier = Modifier,
    /** New tab: unreviewed connections and incoming requests, the set awaiting a decision. */
    showNew: Boolean = false,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (!showNew) FilterRow(uiState.filter, uiState.reviewEnabled, onAction)

        // Incoming requests are the actionable set (outgoing has nothing to do here but Cancel,
        // already reachable from the resolved identity itself). They live with the unreviewed
        // connections because they are the same job: someone is waiting on a decision.
        // No New tab while the review is dark, so they stay atop the Contacts list.
        val incomingRequests = if (showNew || !uiState.reviewEnabled) {
            uiState.requests.filter { it.direction == RequestDirection.INCOMING }
        } else {
            emptyList()
        }

        val derivedFromStates = showNew || uiState.filter == ContactFilter.CIRCLES
        val list = when {
            showNew -> uiState.newContacts
            uiState.filter == ContactFilter.CIRCLES -> uiState.circleContacts
            uiState.filter == ContactFilter.UNVETTED -> uiState.unvetted
            uiState.filter == ContactFilter.VETTED -> uiState.vetted
            uiState.filter == ContactFilter.BLOCKED -> uiState.blockedContacts
            else -> uiState.knownContacts
        }

        when {
            // Only the state-derived views wait on circles: they aren't cached, so gating All on
            // them would strand the whole list behind a spinner whenever circles fail to load.
            uiState.isLoading || (derivedFromStates && uiState.statesLoading) -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            list.isEmpty() && incomingRequests.isEmpty() && uiState.searchQuery.isNotBlank() ->
                CenterText(stringResource(MR.string.contactbook_no_results))

            list.isEmpty() && incomingRequests.isEmpty() -> when {
                showNew -> CenterText(stringResource(MR.string.contactbook_new_empty))

                uiState.filter == ContactFilter.CIRCLES ->
                    CenterText(stringResource(MR.string.contactbook_circles_filter_empty))

                uiState.filter == ContactFilter.UNVETTED ->
                    CenterText(stringResource(MR.string.contactbook_unvetted_empty))

                uiState.filter == ContactFilter.VETTED ->
                    CenterText(stringResource(MR.string.contactbook_vetted_empty))

                uiState.filter == ContactFilter.BLOCKED ->
                    CenterText(stringResource(MR.string.contactbook_blocked_filter_empty))

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
                            val state = entry.odinId?.lowercase()
                                ?.takeIf { uiState.reviewEnabled }
                                ?.let { uiState.contactStates[it] }
                            ContactBookRow(
                                entry = entry,
                                onClick = { onAction(ContactBookUiAction.ContactClicked(entry)) },
                                // Check shows whenever the identity is connected, in every
                                // filter (a New contact is still a connection).
                                connected = entry.odinId?.lowercase() in uiState.connectedOdinIds,
                                trailing = when (state) {
                                    null -> null
                                    // New is the one state with something to do, so it gets the
                                    // action rather than the icon that merely reports the state.
                                    ContactState.New -> {
                                        {
                                            TextButton(
                                                onClick = {
                                                    onAction(ContactBookUiAction.ReviewClicked(entry))
                                                },
                                            ) {
                                                Text(stringResource(MR.string.contact_review_action))
                                            }
                                        }
                                    }

                                    else -> {
                                        { ContactStateIcon(state) }
                                    }
                                },
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
    reviewEnabled: Boolean,
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
        if (reviewEnabled) {
            FilterChip(
                selected = filter == ContactFilter.CIRCLES,
                onClick = { onAction(ContactBookUiAction.FilterChanged(ContactFilter.CIRCLES)) },
                label = { Text(stringResource(MR.string.contactbook_filter_circles)) },
            )
            FilterChip(
                selected = filter == ContactFilter.BLOCKED,
                onClick = { onAction(ContactBookUiAction.FilterChanged(ContactFilter.BLOCKED)) },
                label = { Text(stringResource(MR.string.contactbook_filter_blocked)) },
            )
        } else {
            FilterChip(
                selected = filter == ContactFilter.UNVETTED,
                onClick = { onAction(ContactBookUiAction.FilterChanged(ContactFilter.UNVETTED)) },
                label = { Text(stringResource(MR.string.contactbook_circle_unvetted)) },
            )
            FilterChip(
                selected = filter == ContactFilter.VETTED,
                onClick = { onAction(ContactBookUiAction.FilterChanged(ContactFilter.VETTED)) },
                label = { Text(stringResource(MR.string.contactbook_vetted)) },
            )
        }
    }
}
